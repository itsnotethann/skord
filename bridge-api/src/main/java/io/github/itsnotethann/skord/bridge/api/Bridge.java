package io.github.itsnotethann.skord.bridge.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.protocol.BridgeClient;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import io.github.itsnotethann.skord.protocol.Listen;
import io.github.itsnotethann.skord.protocol.Ref;
import io.github.itsnotethann.skord.protocol.Scope;
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

		/** The bot would not forward these events: outside what its operator allowed this server to listen to. */
		default void onSubscriptionsRejected(List<Frame.SubscribeResult.Rejection> rejected) {}

		/** The bot changed this server's scope while it was connected. */
		default void onScopeChanged(Set<Scope> scope, Set<Listen> listen) {}

		default void onBotFunctions(List<Frame.FunctionSpec> functions) {}
	}

	private final BridgePlatform platform;
	private final BridgeConfig config;
	private final BridgeClient client;
	private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
	private final Map<String, CompletableFuture<Frame.Result>> pending = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Frame.CallResult>> asks = new ConcurrentHashMap<>();
	private final Map<String, FunctionHandler> functions = new ConcurrentHashMap<>();
	private final Map<String, Frame.Players.Player> online = new LinkedHashMap<>();
	private final Map<String, Frame.Players.Player> joinedPending = new LinkedHashMap<>();
	private final Map<String, Frame.Players.Player> leftPending = new LinkedHashMap<>();
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "skord-bridge-players");
		thread.setDaemon(true);
		return thread;
	});
	private ScheduledFuture<?> flush;
	private final List<Function<Frame.Event, CompletionStage<?>>> eventHandlers = new CopyOnWriteArrayList<>();
	private volatile Set<Scope> scope = EnumSet.noneOf(Scope.class);
	private volatile Set<Listen> listen = EnumSet.noneOf(Listen.class);
	private volatile Map<String, Command> commands = Map.of();
	private volatile boolean declared;
	private volatile List<Frame.EventSpec> subscriptions = List.of();
	private volatile boolean subscribed;
	private volatile List<Frame.FunctionSpec> functionSpecs = List.of();
	private volatile boolean functionsDeclared;
	private volatile @Nullable List<Frame.FunctionSpec> botFunctions;
	private volatile State state = State.CONNECTING;
	private volatile @Nullable Frame.BotInfo bot;

	static final long PLAYER_DEBOUNCE_MILLIS = 250;

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
		return start(config, platform, new ConnectionListener[0]);
	}

	/** As {@link #start(BridgeConfig, BridgePlatform)}, with listeners in place before the first connect. */
	public static Bridge start(BridgeConfig config, BridgePlatform platform, ConnectionListener... initial) {
		Bridge bridge = new Bridge(config, platform);
		for (ConnectionListener listener : initial)
			bridge.listeners.add(listener);
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
		this.commands = java.util.Collections.unmodifiableMap(byName);
		this.declared = true;
		publishCommands();
	}

	private void publishCommands() {
		if (!declared || state != State.CONNECTED)
			return;
		client.send(new Frame.Commands(commands.values().stream().map(Command::spec).toList()));
	}

	/**
	 * Declares the Discord events this server's scripts handle, replacing any declared before. Re-sent after every
	 * reconnect. The bot answers with the ones it refused, which {@link ConnectionListener#onSubscriptionsRejected}
	 * reports; the rest apply.
	 */
	public void subscribe(List<Frame.EventSpec> events) {
		this.subscriptions = List.copyOf(events);
		this.subscribed = true;
		publishSubscriptions();
	}

	private void publishSubscriptions() {
		if (!subscribed || state != State.CONNECTED)
			return;
		client.send(new Frame.Subscribe(subscriptions));
	}

	/**
	 * Runs the handler on the main thread for every event the bot forwards. The event is acknowledged once every
	 * handler has returned and the stage each returned (null counts as done) has completed.
	 */
	public void onEvent(Function<Frame.Event, CompletionStage<?>> handler) {
		eventHandlers.add(handler);
	}

	/** Asks the bot to act on Discord. Fails with a {@link BridgeRequestException} carrying the bot's error. */
	public CompletionStage<Frame.Result> action(String kind, JsonObject args) {
		String id = UUID.randomUUID().toString();
		return request(id, new Frame.Action(id, kind, args));
	}

	public Set<Listen> listen() {
		return Set.copyOf(listen);
	}

	public void onCall(String name, FunctionHandler handler) {
		functions.put(name, handler);
	}

	public void removeCall(String name) {
		functions.remove(name);
	}

	public Set<Scope> scope() {
		return Set.copyOf(scope);
	}

	public void declareFunctions(List<Frame.FunctionSpec> declared) {
		this.functionSpecs = List.copyOf(declared);
		this.functionsDeclared = true;
		publishFunctions();
	}

	private void publishFunctions() {
		if (!functionsDeclared || state != State.CONNECTED)
			return;
		client.send(new Frame.Functions(functionSpecs));
	}

	public @Nullable List<Frame.FunctionSpec> botFunctions() {
		return botFunctions;
	}

	public CompletionStage<JsonElement> call(@Nullable String target, String function, JsonElement args) {
		return ask(function, args, target);
	}

	public CompletionStage<JsonElement> callAll(String function, JsonElement args) {
		return ask(function, args, Frame.Call.ALL);
	}

	public boolean send(@Nullable String target, String function, JsonElement args) {
		return state == State.CONNECTED
			&& client.send(new Frame.Call(UUID.randomUUID().toString(), function, args, false, target));
	}

	private CompletionStage<JsonElement> ask(String name, JsonElement data, @Nullable String target) {
		CompletableFuture<JsonElement> answer = new CompletableFuture<>();
		if (state != State.CONNECTED) {
			answer.completeExceptionally(new BridgeRequestException("offline", "The bridge isn't connected to the bot", 0));
			return answer;
		}
		String id = UUID.randomUUID().toString();
		CompletableFuture<Frame.CallResult> reply = new CompletableFuture<>();
		asks.put(id, reply);
		if (!client.send(new Frame.Call(id, name, data, true, target))) {
			asks.remove(id);
			answer.completeExceptionally(new BridgeRequestException("offline", "The bridge isn't connected to the bot", 0));
			return answer;
		}
		reply.orTimeout(config.timeoutMillis(), TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
			asks.remove(id);
			if (error instanceof java.util.concurrent.TimeoutException)
				answer.completeExceptionally(new BridgeRequestException("timed out", "'" + name + "' didn't answer in time", 0));
			else if (error != null)
				answer.completeExceptionally(error instanceof BridgeRequestException ? error
					: new BridgeRequestException("error", String.valueOf(error.getMessage()), 0));
			else if (response.error() != null)
				answer.completeExceptionally(new BridgeRequestException(response.error().type(), response.error().message(),
					response.error().code(), response.error().scope()));
			else
				answer.complete(response.data());
		});
		return answer;
	}

	public void playerJoined(String uuid, String name) {
		changePlayer(new Frame.Players.Player(uuid, name), true);
	}

	public void playerLeft(String uuid, String name) {
		changePlayer(new Frame.Players.Player(uuid, name), false);
	}

	private synchronized void changePlayer(Frame.Players.Player player, boolean joined) {
		String key = player.uuid();
		boolean changed = joined ? online.put(key, player) == null : online.remove(key) != null;
		if (!changed)
			return;
		if (joined) {
			if (leftPending.remove(key) == null)
				joinedPending.put(key, player);
		} else if (joinedPending.remove(key) == null) {
			leftPending.put(key, player);
		}
		if (state == State.CONNECTED && flush == null && !timers.isShutdown())
			flush = timers.schedule(this::flushPlayers, PLAYER_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
	}

	private synchronized void flushPlayers() {
		flush = null;
		if (state != State.CONNECTED || joinedPending.isEmpty() && leftPending.isEmpty())
			return;
		client.send(new Frame.Players(false, new ArrayList<>(joinedPending.values()), new ArrayList<>(leftPending.values())));
		joinedPending.clear();
		leftPending.clear();
	}

	private synchronized void sendSnapshot() {
		if (flush != null) {
			flush.cancel(false);
			flush = null;
		}
		joinedPending.clear();
		leftPending.clear();
		client.send(new Frame.Players(true, new ArrayList<>(online.values()), List.of()));
	}

	private void handleCall(Frame.Call call) {
		FunctionHandler handler = functions.get(call.function());
		if (handler == null) {
			if (call.waiting())
				client.send(new Frame.CallResult(call.id(), com.google.gson.JsonNull.INSTANCE, new Frame.Result.Failure(
					"no such function", "This server has no function '" + call.function() + "'", 0)));
			return;
		}
		platform.runOnMain(() -> {
			CompletionStage<JsonElement> pending;
			try {
				pending = handler.handle(call.args());
			} catch (RuntimeException e) {
				platform.log(Level.SEVERE, "Bridge: function '" + call.function() + "' failed: " + e);
				pending = CompletableFuture.failedFuture(e);
			}
			if (pending == null)
				pending = CompletableFuture.completedFuture(com.google.gson.JsonNull.INSTANCE);
			pending.whenComplete((data, error) -> {
				if (!call.waiting()) {
					if (error != null)
						platform.log(Level.WARNING, "Bridge: function '" + call.function() + "' failed: " + error);
					return;
				}
				if (error != null) {
					Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
						? error.getCause() : error;
					client.send(new Frame.CallResult(call.id(), com.google.gson.JsonNull.INSTANCE,
						new Frame.Result.Failure("error", String.valueOf(cause.getMessage()), 0)));
				} else {
					client.send(new Frame.CallResult(call.id(), data, null));
				}
			});
		});
	}

	@Override
	public void close() {
		state = State.CLOSED;
		bot = null;
		scope = EnumSet.noneOf(Scope.class);
		failPending("closed");
		timers.shutdownNow();
		client.close();
	}

	private void failPending(String reason) {
		for (Map.Entry<String, CompletableFuture<Frame.CallResult>> entry : asks.entrySet()) {
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
					: new BridgeRequestException(failure.type(), failure.message(), failure.code(), failure.scope());
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

		@Override
		public CompletionStage<Void> deleteOriginal() {
			return respond(Frame.Respond.Action.DELETE, null, false, null).thenApply(result -> null);
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
			scope = welcome.scope().isEmpty() ? EnumSet.noneOf(Scope.class) : EnumSet.copyOf(welcome.scope());
			state = State.CONNECTED;
			platform.log(Level.INFO, "Bridge: connected to " + welcome.bot().name());
			publishCommands();
			publishSubscriptions();
			publishFunctions();
			sendSnapshot();
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
				case Frame.Call call -> handleCall(call);
				case Frame.Event event -> platform.runOnMain(() -> {
					List<CompletableFuture<?>> running = new ArrayList<>();
					for (Function<Frame.Event, CompletionStage<?>> handler : eventHandlers) {
						try {
							CompletionStage<?> stage = handler.apply(event);
							if (stage != null)
								running.add(stage.toCompletableFuture());
						} catch (RuntimeException e) {
							platform.log(Level.SEVERE, "Bridge: handling a " + event.kind() + " event failed: " + e);
						}
					}
					CompletableFuture.allOf(running.toArray(new CompletableFuture<?>[0]))
						.whenComplete((ignored, error) -> client.send(new Frame.EventAck(event.id())));
				});
				case Frame.SubscribeResult result -> {
					if (!result.rejected().isEmpty()) {
						for (Frame.SubscribeResult.Rejection rejection : result.rejected())
							platform.log(Level.WARNING, "Bridge: the bot will not forward '" + rejection.kind()
								+ "' events: " + rejection.reason());
						platform.runOnMain(() -> listeners.forEach(listener ->
							listener.onSubscriptionsRejected(result.rejected())));
					}
				}
				case Frame.ScopeUpdate update -> {
					scope = update.scope().isEmpty() ? EnumSet.noneOf(Scope.class) : EnumSet.copyOf(update.scope());
					listen = update.listen().isEmpty() ? EnumSet.noneOf(Listen.class) : EnumSet.copyOf(update.listen());
					platform.log(Level.INFO, "Bridge: the bot changed this server's scope");
					platform.runOnMain(() -> listeners.forEach(listener ->
						listener.onScopeChanged(update.scope(), update.listen())));
				}
				case Frame.Functions declared -> {
					botFunctions = List.copyOf(declared.functions());
					platform.runOnMain(() -> listeners.forEach(listener -> listener.onBotFunctions(declared.functions())));
				}
				case Frame.CallResult response -> {
					CompletableFuture<Frame.CallResult> future = asks.remove(response.id());
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
			botFunctions = null;
			failPending("disconnected");
			if (wasConnected) {
				platform.log(Level.WARNING, "Bridge: lost the bot (" + reason + "); reconnecting");
				platform.runOnMain(() -> listeners.forEach(listener -> listener.onDisconnect(reason)));
			}
		}
	}
}
