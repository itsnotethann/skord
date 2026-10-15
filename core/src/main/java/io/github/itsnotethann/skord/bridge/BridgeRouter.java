package io.github.itsnotethann.skord.bridge;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import io.github.itsnotethann.skord.event.ServerConnectEvent;
import io.github.itsnotethann.skord.event.ServerDisconnectEvent;
import io.github.itsnotethann.skord.rest.KeyedResult;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.interaction.Interaction;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Listen;
import io.github.itsnotethann.skord.protocol.Ref;
import io.github.itsnotethann.skord.protocol.Scope;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.HeldKinds;
import io.github.itsnotethann.skord.discord.RefCodec;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
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
	private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
	private final Map<String, Map<String, String>> players = new ConcurrentHashMap<>();
	private final PlayerLinks playerLinks;
	private final BridgeHost.Settings settings;
	private final Path serversDirectory;
	private final Map<String, HeldQueue> held = new ConcurrentHashMap<>();
	private final Map<String, List<Frame.EventSpec>> heldSpecs = new ConcurrentHashMap<>();
	private final Map<String, List<DiscordEvents.Subscription>> heldSubscriptions = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> inflight = new ConcurrentHashMap<>();
	private final Set<String> ready = ConcurrentHashMap.newKeySet();
	// Bot-to-server requests waiting for their response, by request id.
	private final Map<String, Ask> asks = new ConcurrentHashMap<>();
	private final LinkStore links;
	private final ComponentRoutes components;
	private final Map<String, List<DiscordEvents.Subscription>> subscriptions = new ConcurrentHashMap<>();
	private final Map<String, java.util.concurrent.atomic.AtomicInteger> forwarded = new ConcurrentHashMap<>();
	private final Map<String, java.util.concurrent.atomic.AtomicInteger> refused = new ConcurrentHashMap<>();
	private final Map<String, java.util.concurrent.atomic.AtomicInteger> received = new ConcurrentHashMap<>();

	BridgeRouter(Path serversDirectory, Executor script, BridgeHost.Settings settings, LinkStore links) {
		this.script = script;
		this.store = new RemoteCommandStore(serversDirectory);
		this.timeoutMillis = settings.timeoutMillis();
		this.settings = settings;
		this.serversDirectory = serversDirectory;
		this.playerLinks = new FilePlayerLinks(serversDirectory.resolveSibling("player-links.json"));
		this.links = links;
		this.components = new ComponentRoutes(serversDirectory.resolveSibling("components.json"), ComponentRoutes.CAP);
	}

	/** How many frames of this type the server has sent since the bot started. */
	public int receivedFrames(String name, String type) {
		java.util.concurrent.atomic.AtomicInteger count = received.get(name + "/" + type);
		return count == null ? 0 : count.get();
	}

	/** How many {@code event} frames went to this server since the bot started. */
	public int forwardedEvents(String name) {
		java.util.concurrent.atomic.AtomicInteger count = forwarded.get(name);
		return count == null ? 0 : count.get();
	}

	/** How many subscriptions of this server were refused for lacking a listen word. */
	public int refusedSubscriptions(String name) {
		java.util.concurrent.atomic.AtomicInteger count = refused.get(name);
		return count == null ? 0 : count.get();
	}

	/** The server that sent this message with components, or null when none is recorded. */
	public @Nullable String componentOwner(String messageId) {
		return components.route(messageId);
	}

	public int componentRoutes() {
		return components.size();
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
			Instant seen = store.loadLastSeen(name);
			if (seen != null)
				lastSeen.put(name, seen);
			List<Frame.EventSpec> savedHeld = store.loadHeld(name);
			if (!savedHeld.isEmpty()) {
				heldSpecs.put(name, savedHeld);
				resubscribeHeld(name, savedHeld);
			}
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
		lastSeen.remove(name);
		players.remove(name);
		components.forget(name);
		cancelSubscriptions(name);
		cancelHeld(name);
		heldSpecs.remove(name);
		HeldQueue queue = held.remove(name);
		if (queue != null)
			queue.clear();
		inflight.remove(name);
		ready.remove(name);
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
		lastSeen.clear();
		linkedServers.forEach(this::cancelHeld);
		held.clear();
		heldSpecs.clear();
		inflight.clear();
		playerLinks.clearMemory();
		restore(linkedServers);
	}

	// ---- BridgeServer.Listener: WebSocket threads

	@Override
	public void connected(String name, String platform) {
		script.execute(() -> {
			Instant now = Instant.now();
			lastSeen.put(name, now);
			store.saveLastSeen(name, now);
			players.put(name, new java.util.LinkedHashMap<>());
			inflight.remove(name);
			Skript.eventBus().fire(new ServerConnectEvent(name, now));
		});
	}

	public boolean isOnline(String name) {
		BridgeServer live = server;
		return live != null && live.isConnected(name);
	}

	public List<String> onlineServers() {
		BridgeServer live = server;
		return live == null ? List.of() : live.connectedServers();
	}

	public @Nullable Instant lastSeen(String name) {
		return isOnline(name) ? Instant.now() : lastSeen.get(name);
	}

	public List<String> players(String name) {
		Map<String, String> online = players.get(name);
		return online == null ? List.of() : List.copyOf(online.values());
	}

	public List<String> playerUuids(String name) {
		Map<String, String> online = players.get(name);
		return online == null ? List.of() : List.copyOf(online.keySet());
	}

	public PlayerLinks playerLinks() {
		return playerLinks;
	}

	/** Ids waiting in this server's held queue, oldest first. */
	public List<String> heldIds(String name) {
	return queue(name).ids();
	}

	public List<String> heldDrops(String name) {
	return queue(name).drops();
	}

	private HeldQueue queue(String name) {
		return held.computeIfAbsent(name, key -> new HeldQueue(key, serversDirectory.resolveSibling("held")
			.resolve(key + ".jsonl"), settings.heldMaxEvents(), settings.heldMaxAgeMillis()));
	}

	@Override
	public void disconnected(String name) {
		script.execute(() -> {
			Instant now = Instant.now();
			lastSeen.put(name, now);
			store.saveLastSeen(name, now);
			players.remove(name);
			cancelSubscriptions(name);
			ready.remove(name);
			inflight.remove(name);
			Skript.eventBus().fire(new ServerDisconnectEvent(name, now));
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
		received.computeIfAbsent(name + "/" + frame.type(), key -> new java.util.concurrent.atomic.AtomicInteger())
			.incrementAndGet();
		script.execute(() -> {
			switch (frame) {
				case Frame.Commands commands -> accept(name, commands);
				case Frame.Respond respond -> respond(name, respond);
				case Frame.Dispatched dispatched -> dispatched(name, dispatched);
				case Frame.Call call -> call(name, call);
				case Frame.CallResult result -> response(name, result);
				case Frame.Players update -> players(name, update);
				case Frame.Action action -> action(name, action);
				case Frame.Subscribe subscribe -> subscribe(name, subscribe);
				case Frame.EventAck ack -> {
					Set<String> sent = inflight.get(name);
					if (sent != null)
						sent.remove(ack.id());
					queue(name).ack(ack.id());
				}
				default -> {}
			}
		});
	}

	// ---- script thread

	/** Sends a named message to a server. Fails with an {@code offline} failure if it isn't connected. */
	public java.util.concurrent.CompletionStage<Void> sendMessage(String target, String message, JsonElement data) {
		BridgeServer live = server;
		if (live == null || !live.send(target, new Frame.Call(UUID.randomUUID().toString(), message, data, false)))
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
		if (!live.send(target, new Frame.Call(id, question, data, true))) {
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

	private void action(String name, Frame.Action frame) {
		BridgeServer live = server;
		if (live == null)
			return;
		Scope needed = BridgeActions.scopeOf(frame.kind());
		if (needed != null && !links.allows(name, needed)) {
			LOGGER.warn("Bridge: server '{}' tried '{}' without the '{}' scope", name, frame.kind(), needed.wire);
			live.send(name, BridgeActions.outOfScope(frame.id(), needed));
			return;
		}
		java.util.concurrent.CompletionStage<Frame.Result> stage;
		try {
			stage = BridgeActions.execute(frame);
		} catch (RuntimeException e) {
			stage = java.util.concurrent.CompletableFuture.failedFuture(e);
		}
		stage.whenComplete((result, error) -> {
			Frame.Result answer = error != null ? BridgeActions.failure(frame.id(), error) : result;
			if (error == null && result.ok() && result.message() != null && frame.args().has("components")
				&& (frame.kind().equals("send") || frame.kind().equals("reply")))
				components.record(result.message(), name);
			live.send(name, answer);
		});
	}

	private void cancelSubscriptions(String name) {
		List<DiscordEvents.Subscription> old = subscriptions.remove(name);
		if (old != null)
			old.forEach(DiscordEvents.Subscription::cancel);
	}

	private void cancelHeld(String name) {
		List<DiscordEvents.Subscription> old = heldSubscriptions.remove(name);
		if (old != null)
			old.forEach(DiscordEvents.Subscription::cancel);
	}

	private void resubscribeHeld(String name, List<Frame.EventSpec> specs) {
		cancelHeld(name);
		List<DiscordEvents.Subscription> accepted = new ArrayList<>();
		for (Frame.EventSpec wire : specs) {
			EventSpec.Kind kind = EventSpec.Kind.ofWire(wire.kind());
			if (kind == null || !HeldKinds.heldable(kind))
				continue;
			try {
				accepted.add(Discord.current().events().subscribe(new EventSpec(kind, filtersOf(wire), true),
					event -> enqueue(name, event)));
			} catch (IllegalStateException noDiscord) {
				break;
			}
		}
		heldSubscriptions.put(name, accepted);
	}

	private static java.util.Map<String, String> filtersOf(Frame.EventSpec wire) {
		java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
		wire.filters().entrySet().forEach(entry -> {
			if (entry.getValue().isJsonPrimitive())
				filters.put(entry.getKey(), entry.getValue().getAsString());
		});
		return filters;
	}

	private void subscribe(String name, Frame.Subscribe frame) {
		BridgeServer live = server;
		if (live == null)
			return;
		cancelSubscriptions(name);
		List<DiscordEvents.Subscription> accepted = new ArrayList<>();
		List<Frame.EventSpec> heldNow = new ArrayList<>();
		List<Frame.SubscribeResult.Rejection> rejected = new ArrayList<>();
		for (Frame.EventSpec wire : frame.events()) {
			EventSpec.Kind kind = EventSpec.Kind.ofWire(wire.kind());
			if (kind == null) {
				rejected.add(new Frame.SubscribeResult.Rejection(wire.kind(), "the bot has no event called '"
					+ wire.kind() + "'"));
				LOGGER.warn("Bridge: server '{}' subscribed to unknown event '{}'", name, wire.kind());
				continue;
			}
			Listen needed = kind.listen();
			if (needed != null && !links.listens(name, needed)) {
				rejected.add(new Frame.SubscribeResult.Rejection(wire.kind(), "this link may not listen to "
					+ needed.wire + "; allow it with: sk link scope " + name + " --listen " + needed.wire));
				refused.computeIfAbsent(name, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
				LOGGER.warn("Bridge: refused server '{}' subscription to '{}': its link may not listen to {}", name,
					wire.kind(), needed.wire);
				continue;
			}
			// Ready is local to each process, and slash commands arrive as interactions: nothing to forward.
			if (kind == EventSpec.Kind.READY || kind == EventSpec.Kind.SLASH_COMMAND)
				continue;
			if (wire.held()) {
				if (!HeldKinds.heldable(kind)) {
					rejected.add(new Frame.SubscribeResult.Rejection(wire.kind(), "'" + wire.kind()
						+ "' events cannot be held: they have no stable id"));
					continue;
				}
				heldNow.add(wire);
				continue;
			}
			EventSpec spec = new EventSpec(kind, filtersOf(wire), false);
			accepted.add(Discord.current().events().subscribe(spec, event -> forward(name, event)));
		}
		subscriptions.put(name, accepted);
		if (!heldNow.equals(heldSpecs.get(name))) {
			heldSpecs.put(name, List.copyOf(heldNow));
			store.saveHeld(name, heldNow);
		}
		resubscribeHeld(name, heldNow);
		live.send(name, new Frame.SubscribeResult(rejected));
		ready.add(name);
		flush(name);
	}

	private void enqueue(String name, DiscordEvent event) {
		String id = event.id() != null ? event.id() : HeldKinds.id(event.kind(), event.data());
		if (id == null) {
			LOGGER.warn("Bridge: a held '{}' event for '{}' had no id and was dropped", event.kind().wire(), name);
			return;
		}
		JsonObject data = new JsonObject();
		event.data().forEach((key, value) -> data.add(key, RefCodec.valueToJson(value)));
		if (queue(name).add(id, event.kind().wire(), data, System.currentTimeMillis()))
			flush(name);
	}

	/** Sends this server every queued event it has not been sent on this connection, oldest first. */
	private void flush(String name) {
		BridgeServer live = server;
		if (live == null || !ready.contains(name) || !live.isConnected(name))
			return;
		List<Frame.EventSpec> wanted = heldSpecs.getOrDefault(name, List.of());
		Set<String> sent = inflight.computeIfAbsent(name, key -> ConcurrentHashMap.newKeySet());
		HeldQueue queue = queue(name);
		for (HeldQueue.Entry entry : queue.pending(System.currentTimeMillis())) {
			if (sent.contains(entry.id()) || wanted.stream().noneMatch(spec -> spec.kind().equals(entry.kind())))
				continue;
			if (!live.send(name, new Frame.Event(entry.id(), entry.kind(), entry.data(), entry.sent())))
				return;
			sent.add(entry.id());
			queue.markSent(entry.id());
			forwarded.computeIfAbsent(name, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
		}
	}

	private void forward(String name, DiscordEvent event) {
		BridgeServer live = server;
		if (live == null)
			return;
		JsonObject data = new JsonObject();
		event.data().forEach((key, value) -> data.add(key, RefCodec.valueToJson(value)));
		if (live.send(name, new Frame.Event(UUID.randomUUID().toString(), event.kind().wire(), data, false)))
			forwarded.computeIfAbsent(name, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
	}

	private void call(String name, Frame.Call call) {
		if (call.waiting())
			request(name, call);
		else
			Skript.eventBus().fire(new BridgeMessageEvent(name, call.function(), BridgeData.toSkript(call.args())));
	}

	private void players(String name, Frame.Players update) {
		Map<String, String> online = players.computeIfAbsent(name, key -> new java.util.LinkedHashMap<>());
		if (update.snapshot())
			online.clear();
		update.joined().forEach(player -> online.put(player.uuid(), player.name()));
		update.left().forEach(player -> online.remove(player.uuid()));
	}

	private void response(String name, Frame.CallResult frame) {
		Ask ask = asks.get(frame.id());
		if (ask == null || !ask.server().equals(name) || !asks.remove(frame.id(), ask))
			return;
		if (frame.error() != null)
			ask.future().completeExceptionally(new BridgeFailure("refused", frame.error().message()));
		else
			ask.future().complete(BridgeData.toSkript(frame.data()));
	}

	private void request(String name, Frame.Call frame) {
		BridgeServer live = server;
		if (live == null)
			return;
		BridgeRequestEvent event = new BridgeRequestEvent(name, frame.function(), BridgeData.toSkript(frame.args()));
		Skript.eventBus().fire(event);
		// No script answered, and none is waiting on something that could: say so now, not at the asker's timeout.
		if (!event.answer().isDone() && !Delay.isDelayed(event)) {
			live.send(name, new Frame.CallResult(frame.id(), com.google.gson.JsonNull.INSTANCE, new Frame.Result.Failure(
				"no-handler", "The bot has no answer for '" + frame.function() + "'", 0)));
			return;
		}
		event.answer().whenComplete((data, error) -> live.send(name, error != null
			? new Frame.CallResult(frame.id(), com.google.gson.JsonNull.INSTANCE,
				new Frame.Result.Failure("error", String.valueOf(error.getMessage()), 0))
			: new Frame.CallResult(frame.id(), data, null)));
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
	 * The owner a shared command goes to: where the invoking user's linked player is online, else the first online
	 * owner in {@code bridge.route-order}, else the first online owner. Null when none is online.
	 */
	public @Nullable String route(List<String> owners, @Nullable String discordId) {
		List<String> online = owners.stream().filter(this::isOnline).toList();
		if (online.isEmpty())
			return null;
		String uuid = discordId == null ? null : playerLinks.uuid(discordId);
		if (uuid != null) {
			for (String owner : online) {
				if (playerUuids(owner).contains(uuid))
					return owner;
			}
		}
		for (String preferred : settings.routeOrder()) {
			if (online.contains(preferred))
				return preferred;
		}
		return online.get(0);
	}

	/** Sends a slash command that servers own to the one {@link #route} picks, or answers that none is online. */
	public void forward(List<String> owners, SlashEvent slash, boolean locallyPaused) {
		UserRef user = slash.user();
		String chosen = route(owners, user == null ? null : Long.toString(user.id()));
		if (chosen != null) {
			forward(chosen, slash, locallyPaused);
		} else if (owners.size() == 1) {
			forward(owners.get(0), slash, locallyPaused);
		} else {
			slash.interaction().reply("None of the servers that own this command is online right now.", true, false);
			slash.interaction().afterDispatch(locallyPaused);
		}
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
				case DELETE -> interaction.deleteOriginal();
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
					failure.type().label(), failure.message(), failure.code()));
			} else {
				answer = new Frame.Result(frame.id(), true,
					result instanceof MessageRef message ? Long.toString(message.id()) : null, null);
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
			case MessageRef message -> null;
			case DiscordRef ref -> {
				String name = ref.name();
				yield new Ref(Long.toString(ref.id()), name != null ? name : Long.toString(ref.id()));
			}
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
