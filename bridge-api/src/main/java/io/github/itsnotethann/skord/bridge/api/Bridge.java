package io.github.itsnotethann.skord.bridge.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.protocol.BridgeClient;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import io.github.itsnotethann.skord.protocol.Ref;
import org.jetbrains.annotations.Nullable;

/**
 * A game server's connection to its bot. Start one with {@link #start}; it connects out, reconnects with
 * backoff, and tells its listeners when the bot comes and goes. Listeners and command handlers run on the
 * platform's main thread.
 */
public final class Bridge implements AutoCloseable {

	public enum State {
		/** Connecting, or waiting to retry. */
		CONNECTING,
		CONNECTED,
		/** The bot refused this server. The bridge won't retry: fix the secret or name and restart. */
		DENIED,
		CLOSED
	}

	public interface ConnectionListener {

		default void onConnect(Frame.BotInfo bot) {}

		default void onDisconnect(String reason) {}

		/** The bot refused the connection. See {@link Frame.Denied} for the reasons. */
		default void onDenied(String reason) {}

		/** The bot took the declared commands except these, which clash with another owner's or are invalid. */
		default void onCommandsRejected(List<Frame.CommandsResult.Rejection> rejected) {}
	}

	private final BridgePlatform platform;
	private final BridgeConfig config;
	private final BridgeClient client;
	private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
	private final Map<String, CompletableFuture<Frame.Result>> pending = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Frame.Response>> asks = new ConcurrentHashMap<>();
	private final Map<String, MessageHandler> messageHandlers = new ConcurrentHashMap<>();
	private final Map<String, RequestHandler> requestHandlers = new ConcurrentHashMap<>();
	private volatile Map<String, Command> commands = Map.of();
	private volatile boolean declared;
	private volatile State state = State.CONNECTING;
	private volatile @Nullable Frame.BotInfo bot;

	private Bridge(BridgeConfig config, BridgePlatform platform) {
		this.platform = platform;
		this.config = config;
		URI url = config.url();
		this.client = new BridgeClient(url,
			() -> new Frame.Hello(FrameCodec.VERSION, config.name(), config.secret(), platform.name()),
			new Relay());
	}

	/** Connects in the background and returns at once. */
	public static Bridge start(BridgeConfig config, BridgePlatform platform) {
		Bridge bridge = new Bridge(config, platform);
		platform.log(Level.INFO, "Bridge: connecting to " + config.url() + " as '" + config.name() + "'");
		bridge.client.start();
		return bridge;
	}

	public State state() {
		return state;
	}

	public boolean connected() {
		return state == State.CONNECTED;
	}

	/** The bot we're connected to, or null while we aren't. */
	public @Nullable Frame.BotInfo bot() {
		return bot;
	}

	public void addListener(ConnectionListener listener) {
		listeners.add(listener);
	}

	public void removeListener(ConnectionListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Declares the server's whole set of Discord commands, replacing any declared before. Call it after every
	 * load or reload with the full set, once everything is registered. Nothing is sent before the first call, so a
	 * server that is still starting never tells the bot it has no commands.
	 */
	public void declareCommands(List<Command> declared) {
		Map<String, Command> byName = new LinkedHashMap<>();
		for (Command command : declared)
			byName.put(command.spec().name(), command);
		this.commands = Map.copyOf(byName);
		this.declared = true;
		publishCommands();
	}

	private void publishCommands() {
		if (!declared || state != State.CONNECTED)
			return;
		client.send(new Frame.Commands(commands.values().stream().map(Command::spec).toList()));
	}

	/** Runs {@code handler} for each message the bot sends under {@code name}, replacing any earlier one. */
	public void onMessage(String name, MessageHandler handler) {
		messageHandlers.put(name, handler);
	}

	public void removeMessageHandler(String name) {
		messageHandlers.remove(name);
	}

	/** Answers the bot's requests named {@code name}, replacing any earlier handler. */
	public void onRequest(String name, RequestHandler handler) {
		requestHandlers.put(name, handler);
	}

	public void removeRequestHandler(String name) {
		requestHandlers.remove(name);
	}

	/** Sends the bot a named message. Returns false, and sends nothing, if the bridge isn't connected. */
	public boolean send(String name, JsonElement data) {
		return state == State.CONNECTED && client.send(new Frame.Message(name, data));
	}

	/**
	 * Asks the bot and completes with its answer. Fails with a {@link BridgeRequestException} if the bridge isn't
	 * connected ({@code offline}), the bot has no answer ({@code refused}), or it doesn't answer within the
	 * configured timeout ({@code timed out}).
	 */
	public CompletionStage<JsonElement> ask(String name, JsonElement data) {
		CompletableFuture<JsonElement> answer = new CompletableFuture<>();
		if (state != State.CONNECTED) {
			answer.completeExceptionally(new BridgeRequestException("offline", "The bridge isn't connected to the bot", 0));
			return answer;
		}
		String id = UUID.randomUUID().toString();
		CompletableFuture<Frame.Response> reply = new CompletableFuture<>();
		asks.put(id, reply);
		if (!client.send(new Frame.Request(id, name, data))) {
			asks.remove(id);
			answer.completeExceptionally(new BridgeRequestException("offline", "The bridge isn't connected to the bot", 0));
			return answer;
		}
		reply.orTimeout(config.timeoutMillis(), TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
			asks.remove(id);
			if (error instanceof java.util.concurrent.TimeoutException)
				answer.completeExceptionally(new BridgeRequestException("timed out", "The bot didn't answer in time", 0));
			else if (error != null)
				answer.completeExceptionally(error instanceof BridgeRequestException ? error
					: new BridgeRequestException("error", String.valueOf(error.getMessage()), 0));
			else if (response.error() != null)
				answer.completeExceptionally(new BridgeRequestException("refused", response.error().message(),
					response.error().code()));
			else
				answer.complete(response.data());
		});
		return answer;
	}

	private void answerRequest(Frame.Request request) {
		RequestHandler handler = requestHandlers.get(request.name());
		if (handler == null) {
			client.send(new Frame.Response(request.id(), com.google.gson.JsonNull.INSTANCE, new Frame.Result.Failure(
				"no-handler", "This server has no answer for '" + request.name() + "'", 0)));
			return;
		}
		platform.runOnMain(() -> {
			CompletionStage<JsonElement> pending;
			try {
				pending = handler.handle(request.data());
			} catch (RuntimeException e) {
				platform.log(Level.SEVERE, "Bridge: request '" + request.name() + "' failed: " + e);
				pending = CompletableFuture.failedFuture(e);
			}
			if (pending == null)
				pending = CompletableFuture.completedFuture(com.google.gson.JsonNull.INSTANCE);
			pending.whenComplete((data, error) -> {
				if (error != null) {
					Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
						? error.getCause() : error;
					client.send(new Frame.Response(request.id(), com.google.gson.JsonNull.INSTANCE,
						new Frame.Result.Failure("error", String.valueOf(cause.getMessage()), 0)));
				} else {
					client.send(new Frame.Response(request.id(), data, null));
				}
			});
		});
	}

	@Override
	public void close() {
		state = State.CLOSED;
		bot = null;
		failPending("closed");
		client.close();
	}

	private void failPending(String reason) {
		for (Map.Entry<String, CompletableFuture<Frame.Response>> entry : asks.entrySet()) {
			if (asks.remove(entry.getKey(), entry.getValue()))
				entry.getValue().completeExceptionally(new BridgeRequestException("offline",
					"The bridge was " + reason + " before the bot answered", 0));
		}
		for (Map.Entry<String, CompletableFuture<Frame.Result>> entry : pending.entrySet()) {
			if (pending.remove(entry.getKey(), entry.getValue()))
				entry.getValue().completeExceptionally(new BridgeRequestException("disconnected",
					"The bridge was " + reason + " before the bot answered", 0));
		}
	}

	/** Sends a request and waits for its {@code result}, up to the configured timeout. */
	private CompletionStage<Frame.Result> request(String id, Frame frame) {
		CompletableFuture<Frame.Result> future = new CompletableFuture<>();
		pending.put(id, future);
		if (!client.send(frame)) {
			pending.remove(id);
			future.completeExceptionally(new BridgeRequestException("disconnected",
				"The bridge isn't connected to the bot", 0));
			return future;
		}
		future.orTimeout(config.timeoutMillis(), TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
			pending.remove(id);
		});
		return future.handle((result, error) -> {
			if (error != null) {
				Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
					? error.getCause() : error;
				if (cause instanceof java.util.concurrent.TimeoutException)
					throw new BridgeRequestException("timed out", "The bot didn't answer in time", 0);
				if (cause instanceof BridgeRequestException exception)
					throw exception;
				throw new BridgeRequestException("error", String.valueOf(cause.getMessage()), 0);
			}
			if (!result.ok()) {
				Frame.Result.Failure failure = result.error();
				throw failure == null
					? new BridgeRequestException("error", "The bot refused the request", 0)
					: new BridgeRequestException(failure.type(), failure.message(), failure.code());
			}
			return result;
		});
	}

	private final class Context implements CommandContext {

		private final Frame.Interaction frame;
		private final Map<String, Object> options;

		Context(Frame.Interaction frame, Map<String, Object> options) {
			this.frame = frame;
			this.options = options;
		}

		@Override
		public String command() {
			return frame.command();
		}

		@Override
		public Map<String, Object> options() {
			return options;
		}

		@Override
		public @Nullable Ref user() {
			return frame.user();
		}

		@Override
		public @Nullable Ref member() {
			return frame.member();
		}

		@Override
		public @Nullable Ref guild() {
			return frame.guild();
		}

		@Override
		public @Nullable Ref channel() {
			return frame.channel();
		}

		private CompletionStage<String> respond(Frame.Respond.Action action, @Nullable String text, boolean ephemeral,
												@Nullable String message) {
			String id = UUID.randomUUID().toString();
			return request(id, new Frame.Respond(id, frame.id(), action, text, ephemeral, message))
				.thenApply(result -> result.message() == null ? "" : result.message());
		}

		@Override
		public CompletionStage<String> reply(String text, boolean ephemeral) {
			return respond(Frame.Respond.Action.REPLY, text, ephemeral, null);
		}

		@Override
		public CompletionStage<Void> defer(boolean ephemeral) {
			return respond(Frame.Respond.Action.DEFER, null, ephemeral, null).thenApply(result -> null);
		}

		@Override
		public CompletionStage<String> edit(@Nullable String messageId, String text) {
			return respond(Frame.Respond.Action.EDIT, text, false, messageId);
		}

		@Override
		public CompletionStage<String> followUp(String text, boolean ephemeral) {
			return respond(Frame.Respond.Action.FOLLOW_UP, text, ephemeral, null);
		}
	}

	// Values arrive as JSON; the declared type decides whether 3 is an integer or a number.
	private static Map<String, Object> options(CommandSpec spec, JsonObject values) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (CommandSpec.Option option : spec.options()) {
			JsonElement value = values.get(option.name());
			if (value == null || value.isJsonNull())
				continue;
			try {
				out.put(option.name(), switch (option.kind()) {
					case TEXT -> value.getAsString();
					case INTEGER -> value.getAsLong();
					case NUMBER -> value.getAsDouble();
					case BOOLEAN -> value.getAsBoolean();
					case USER, MEMBER, ROLE, CHANNEL, CATEGORY -> new Ref(
						value.getAsJsonObject().get("id").getAsString(),
						value.getAsJsonObject().has("name") ? value.getAsJsonObject().get("name").getAsString() : "");
				});
			} catch (RuntimeException malformed) {
				// A value that doesn't match its declared type is left out rather than failing the command.
			}
		}
		return out;
	}

	private void interaction(Frame.Interaction frame) {
		Command command = commands.get(frame.command());
		if (command == null) {
			String id = UUID.randomUUID().toString();
			client.send(new Frame.Respond(id, frame.id(), Frame.Respond.Action.REPLY,
				"That command isn't available on this server right now.", true, null));
			client.send(new Frame.Dispatched(frame.id(), false));
			return;
		}
		Context context = new Context(frame, options(command.spec(), frame.options()));
		platform.runOnMain(() -> {
			boolean paused = false;
			try {
				CompletionStage<?> later = command.handler().handle(context);
				paused = later != null && !later.toCompletableFuture().isDone();
			} catch (RuntimeException e) {
				platform.log(Level.SEVERE, "Bridge: command /" + frame.command() + " failed: " + e);
				context.reply("Something went wrong running that command.", true);
			}
			client.send(new Frame.Dispatched(frame.id(), paused));
		});
	}

	private final class Relay implements BridgeClient.Listener {

		@Override
		public void onWelcome(Frame.Welcome welcome) {
			if (state == State.CLOSED)
				return;
			bot = welcome.bot();
			state = State.CONNECTED;
			platform.log(Level.INFO, "Bridge: connected to " + welcome.bot().name());
			publishCommands();
			platform.runOnMain(() -> listeners.forEach(listener -> listener.onConnect(welcome.bot())));
		}

		@Override
		public void onDenied(Frame.Denied denied) {
			state = State.DENIED;
			bot = null;
			platform.log(Level.SEVERE, "Bridge: the bot refused this server (" + denied.reason() + ")");
			platform.runOnMain(() -> listeners.forEach(listener -> listener.onDenied(denied.reason())));
		}

		@Override
		public void onFrame(Frame frame) {
			switch (frame) {
				case Frame.Result result -> {
					CompletableFuture<Frame.Result> future = pending.remove(result.id());
					if (future != null)
						future.complete(result);
				}
				case Frame.Interaction interaction -> interaction(interaction);
				case Frame.Message message -> {
					MessageHandler handler = messageHandlers.get(message.name());
					if (handler != null)
						platform.runOnMain(() -> handler.handle(message.data()));
				}
				case Frame.Request request -> answerRequest(request);
				case Frame.Response response -> {
					CompletableFuture<Frame.Response> future = asks.remove(response.id());
					if (future != null)
						future.complete(response);
				}
				case Frame.CommandsResult result -> {
					if (!result.rejected().isEmpty()) {
						for (Frame.CommandsResult.Rejection rejection : result.rejected())
							platform.log(Level.WARNING, "Bridge: the bot rejected /" + rejection.command() + ": "
								+ rejection.reason());
						platform.runOnMain(() -> listeners.forEach(listener -> listener.onCommandsRejected(result.rejected())));
					}
				}
				default -> {}
			}
		}

		@Override
		public void onDisconnect(String reason) {
			if (state == State.CLOSED || state == State.DENIED)
				return;
			boolean wasConnected = state == State.CONNECTED;
			state = State.CONNECTING;
			bot = null;
			failPending("disconnected");
			if (wasConnected) {
				platform.log(Level.WARNING, "Bridge: lost the bot (" + reason + "); reconnecting");
				platform.runOnMain(() -> listeners.forEach(listener -> listener.onDisconnect(reason)));
			}
		}
	}
}
