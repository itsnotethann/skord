package io.github.itsnotethann.skord.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ch.njol.skript.Skript;
import ch.njol.skript.effects.Delay;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.event.BridgeMessageEvent;
import io.github.itsnotethann.skord.event.BridgeRequestEvent;
import io.github.itsnotethann.skord.rest.KeyedResult;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Ref;
import io.github.itsnotethann.skord.rest.DiscordError;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects the bridge to the bot's own machinery. A server's commands are merged into {@link CommandRegistry}
 * under its name; when one is used, the interaction goes to the server, and what the server answers is applied to
 * the same {@link Interaction} a script's reply would use, so the ack rules and the three-second watchdog are
 * unchanged.
 * <p>
 * Frames arrive on WebSocket threads and are handed to {@code script} (the script thread) at once, so the
 * registry and every {@link Interaction} are only touched there.
 */
public final class BridgeRouter implements BridgeServer.Listener {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	/** Discord keeps an interaction's webhook for 15 minutes; after that nothing can answer it. */
	private static final long LIFETIME_MILLIS = 15 * 60_000;

	private static final class Pending {
		final String server;
		final Interaction interaction;
		boolean dispatched;

		Pending(String server, Interaction interaction) {
			this.server = server;
			this.interaction = interaction;
		}
	}

	private record Ask(String server, java.util.concurrent.CompletableFuture<KeyedResult> future) {}

	private static volatile @Nullable BridgeRouter current;

	private final Executor script;
	private final RemoteCommandStore store;
	private final long timeoutMillis;
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "skord-bridge-router");
		thread.setDaemon(true);
		return thread;
	});
	private volatile @Nullable BridgeServer server;
	// Bot-to-server requests waiting for their response, by request id.
	private final Map<String, Ask> asks = new ConcurrentHashMap<>();

	BridgeRouter(java.nio.file.Path serversDirectory, Executor script, long timeoutMillis) {
		this.script = script;
		this.store = new RemoteCommandStore(serversDirectory);
		this.timeoutMillis = timeoutMillis;
	}

	/** The router a slash command for a server-owned command goes through, if the bridge is running. */
	public static @Nullable BridgeRouter current() {
		return current;
	}

	void attach(BridgeServer server) {
		this.server = server;
		current = this;
	}

	/** Registers every linked server's saved commands. Call before scripts load. */
	void restore(List<String> linkedServers) {
		for (String name : linkedServers) {
			List<CommandSpec> saved = store.load(name);
			if (saved.isEmpty())
				continue;
			List<Frame.CommandsResult.Rejection> rejected = CommandRegistry.replaceRemote(name, saved);
			for (Frame.CommandsResult.Rejection rejection : rejected)
				LOGGER.warn("Saved command /{} of server '{}' was not restored: {}", rejection.command(), name,
					rejection.reason());
		}
	}

	/** Drops a server's commands: its link was revoked. */
	public void forget(String name) {
		store.delete(name);
		script.execute(() -> {
			if (CommandRegistry.forgetRemote(name))
				CommandSync.request();
		});
	}

	/**
	 * Forgets every remote command in memory and loads the saved ones again, as a fresh process would. Test hook
	 * for "restarting the bot leaves the servers' commands in place".
	 */
	public void reloadFromDisk(List<String> linkedServers) {
		CommandRegistry.clearRemote();
		restore(linkedServers);
	}

	// ---- BridgeServer.Listener: WebSocket threads

	@Override
	public void connected(String name, String platform) {}

	@Override
	public void disconnected(String name) {
		script.execute(() -> {
			for (Map.Entry<String, Ask> entry : asks.entrySet()) {
				if (entry.getValue().server().equals(name) && asks.remove(entry.getKey(), entry.getValue()))
					entry.getValue().future().completeExceptionally(
						new BridgeFailure("offline", "The server '" + name + "' disconnected before it answered"));
			}
			for (Map.Entry<String, Pending> entry : pending.entrySet()) {
				Pending lost = entry.getValue();
				if (!lost.server.equals(name) || !pending.remove(entry.getKey(), lost))
					continue;
				if (lost.interaction.ack() == Interaction.Ack.REPLIED)
					continue;
				lost.interaction.reply("The server '" + name + "' disconnected before it answered.", true, false);
			}
		});
	}

	@Override
	public void frame(String name, Frame frame) {
		script.execute(() -> {
			switch (frame) {
				case Frame.Commands commands -> accept(name, commands);
				case Frame.Respond respond -> respond(name, respond);
				case Frame.Dispatched dispatched -> dispatched(name, dispatched);
				case Frame.Message message -> Skript.eventBus().fire(
					new BridgeMessageEvent(name, message.name(), BridgeData.toSkript(message.data())));
				case Frame.Request request -> request(name, request);
				case Frame.Response response -> response(name, response);
				default -> {}
			}
		});
	}

	// ---- script thread

	/** Sends a named message to a server. Fails with an {@code offline} failure if it isn't connected. */
	public java.util.concurrent.CompletionStage<Void> sendMessage(String target, String message, JsonElement data) {
		BridgeServer live = server;
		if (live == null || !live.send(target, new Frame.Message(message, data)))
			return java.util.concurrent.CompletableFuture.failedFuture(
				new BridgeFailure("offline", "The server '" + target + "' is offline right now"));
		return java.util.concurrent.CompletableFuture.completedFuture(null);
	}

	/**
	 * Asks a server something and completes with its answer, as values and keys ready for a script to store. Fails
	 * with {@code offline}, {@code timed out} or {@code refused}.
	 */
	public java.util.concurrent.CompletionStage<KeyedResult> ask(String target, String question, JsonElement data) {
		BridgeServer live = server;
		if (live == null || !live.isConnected(target))
			return java.util.concurrent.CompletableFuture.failedFuture(
				new BridgeFailure("offline", "The server '" + target + "' is offline right now"));
		String id = UUID.randomUUID().toString();
		java.util.concurrent.CompletableFuture<KeyedResult> future = new java.util.concurrent.CompletableFuture<>();
		asks.put(id, new Ask(target, future));
		if (!live.send(target, new Frame.Request(id, question, data))) {
			asks.remove(id);
			return java.util.concurrent.CompletableFuture.failedFuture(
				new BridgeFailure("offline", "The server '" + target + "' is offline right now"));
		}
		timers.schedule(() -> {
			if (asks.remove(id) != null)
				future.completeExceptionally(new BridgeFailure("timed out", "The server '" + target
					+ "' didn't answer in time"));
		}, timeoutMillis, TimeUnit.MILLISECONDS);
		return future;
	}

	private void response(String name, Frame.Response frame) {
		Ask ask = asks.get(frame.id());
		if (ask == null || !ask.server().equals(name) || !asks.remove(frame.id(), ask))
			return;
		if (frame.error() != null)
			ask.future().completeExceptionally(new BridgeFailure("refused", frame.error().message()));
		else
			ask.future().complete(BridgeData.toSkript(frame.data()));
	}

	private void request(String name, Frame.Request frame) {
		BridgeServer live = server;
		if (live == null)
			return;
		BridgeRequestEvent event = new BridgeRequestEvent(name, frame.name(), BridgeData.toSkript(frame.data()));
		Skript.eventBus().fire(event);
		// No script answered, and none is waiting on something that could: say so now, not at the asker's timeout.
		if (!event.answer().isDone() && !Delay.isDelayed(event)) {
			live.send(name, new Frame.Response(frame.id(), com.google.gson.JsonNull.INSTANCE, new Frame.Result.Failure(
				"no-handler", "The bot has no answer for '" + frame.name() + "'", 0)));
			return;
		}
		event.answer().whenComplete((data, error) -> live.send(name, error != null
			? new Frame.Response(frame.id(), com.google.gson.JsonNull.INSTANCE,
				new Frame.Result.Failure("error", String.valueOf(error.getMessage()), 0))
			: new Frame.Response(frame.id(), data, null)));
	}

	private void accept(String name, Frame.Commands commands) {
		List<Frame.CommandsResult.Rejection> rejected = CommandRegistry.replaceRemote(name, commands.commands());
		store.save(name, CommandRegistry.remoteSpecs(name));
		BridgeServer live = server;
		if (live != null)
			live.send(name, new Frame.CommandsResult(rejected));
		LOGGER.info("Bridge: server '{}' declared {} command(s){}", name, commands.commands().size(),
			rejected.isEmpty() ? "" : ", " + rejected.size() + " rejected");
		CommandSync.request();
	}

	/**
	 * Sends a slash command that a server owns to that server. {@code locallyPaused} is whether a bot script's own
	 * {@code on slash command} handler paused; the interaction is only judged finished once both sides are done.
	 */
	public void forward(String owner, SlashEvent slash, boolean locallyPaused) {
		Interaction interaction = slash.interaction();
		BridgeServer live = server;
		if (live == null || !live.isConnected(owner)) {
			interaction.reply("The server '" + owner + "' is offline right now.", true, false);
			interaction.afterDispatch(locallyPaused);
			return;
		}
		String id = UUID.randomUUID().toString();
		Pending entry = new Pending(owner, interaction);
		pending.put(id, entry);
		JsonObject options = new JsonObject();
		slash.options().forEach((key, value) -> put(options, key, value));
		boolean sent = live.send(owner, new Frame.Interaction(id, slash.command(), options, ref(slash.user()),
			ref(slash.member()), ref(slash.guild()), ref(slash.channel())));
		if (!sent) {
			pending.remove(id);
			interaction.reply("The server '" + owner + "' is offline right now.", true, false);
			interaction.afterDispatch(locallyPaused);
			return;
		}
		// The server owes a 'dispatched' within the timeout. Past it, say so rather than leave "thinking...".
		timers.schedule(() -> script.execute(() -> {
			Pending waiting = pending.get(id);
			if (waiting != null && !waiting.dispatched && interaction.ack() != Interaction.Ack.REPLIED) {
				pending.remove(id);
				interaction.reply("The server '" + owner + "' didn't answer in time.", true, false);
			}
		}), timeoutMillis, TimeUnit.MILLISECONDS);
		timers.schedule(() -> pending.remove(id), LIFETIME_MILLIS, TimeUnit.MILLISECONDS);
	}

	private void dispatched(String name, Frame.Dispatched frame) {
		Pending entry = pending.get(frame.interaction());
		if (entry == null || !entry.server.equals(name) || entry.dispatched)
			return;
		entry.dispatched = true;
		entry.interaction.afterDispatch(frame.paused());
	}

	private void respond(String name, Frame.Respond frame) {
		BridgeServer live = server;
		if (live == null)
			return;
		Pending entry = pending.get(frame.interaction());
		if (entry == null || !entry.server.equals(name)) {
			live.send(name, new Frame.Result(frame.id(), false, null,
				new Frame.Result.Failure("expired", "That interaction is no longer open", 0)));
			return;
		}
		Interaction interaction = entry.interaction;
		String text = frame.text() == null ? "" : frame.text();
		java.util.concurrent.CompletionStage<?> request;
		try {
			request = switch (frame.action()) {
				case REPLY, FOLLOW_UP -> interaction.reply(text, frame.ephemeral(), true);
				case DEFER -> interaction.defer(frame.ephemeral());
				case EDIT -> frame.message() == null
					? interaction.editOriginal(text)
					: interaction.editMessage(frame.message(), text);
			};
		} catch (RuntimeException e) {
			request = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		request.whenComplete((result, error) -> {
			Frame.Result answer;
			if (error != null) {
				DiscordError failure = DiscordError.of(unwrap(error));
				answer = new Frame.Result(frame.id(), false, null, new Frame.Result.Failure(
					failure.type().name().toLowerCase(Locale.ROOT).replace('_', ' '), failure.message(), failure.code()));
			} else {
				answer = new Frame.Result(frame.id(), true,
					result instanceof Message message ? message.getId() : null, null);
			}
			live.send(name, answer);
		});
	}

	private static Throwable unwrap(Throwable error) {
		while ((error instanceof java.util.concurrent.CompletionException) && error.getCause() != null)
			error = error.getCause();
		return error;
	}

	static @Nullable Ref ref(@Nullable Object object) {
		return switch (object) {
			case null -> null;
			case Member member -> new Ref(member.getId(), member.getEffectiveName());
			case User user -> new Ref(user.getId(), user.getName());
			case net.dv8tion.jda.api.entities.Guild guild -> new Ref(guild.getId(), guild.getName());
			case Channel channel -> new Ref(channel.getId(), channel.getName());
			case net.dv8tion.jda.api.entities.Role role -> new Ref(role.getId(), role.getName());
			case IMentionable mentionable -> new Ref(mentionable.getId(), mentionable.getAsMention());
			default -> null;
		};
	}

	private static void put(JsonObject options, String key, @Nullable Object value) {
		switch (value) {
			case null -> {}
			case String text -> options.addProperty(key, text);
			case Number number -> options.addProperty(key, number);
			case Boolean bool -> options.addProperty(key, bool);
			default -> {
				Ref ref = ref(value);
				if (ref != null)
					options.add(key, ref.toJson());
			}
		}
	}
}
