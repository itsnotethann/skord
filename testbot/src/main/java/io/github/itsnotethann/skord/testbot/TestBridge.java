package io.github.itsnotethann.skord.testbot;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import io.github.itsnotethann.skord.ScriptThread;
import io.github.itsnotethann.skord.bridge.BridgeHost;
import io.github.itsnotethann.skord.bridge.BridgeRouter;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.bridge.api.Command;
import io.github.itsnotethann.skord.bridge.api.CommandContext;
import io.github.itsnotethann.skord.bridge.api.CommandHandler;
import io.github.itsnotethann.skord.command.CommandRegistry;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.SpecParser;

/**
 * Game servers for the bot to talk to: real {@link Bridge}s, in this process, over a real local WebSocket to the
 * bot's real {@link BridgeHost}. Scripts drive them with the fake-server effects in {@link TestSyntax}.
 */
final class TestBridge {

	/** The request timeout the bot is started with: short, so the "server never answered" case stays quick. */
	static final long TIMEOUT_MILLIS = 700;

	static final int HELD_MAX_EVENTS = 5;

	private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testbot-bridge");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, FakeServer> SERVERS = new ConcurrentHashMap<>();

	private static volatile int port;
	private static volatile BridgeHost host;
	private static volatile Path workDirectory;

	private TestBridge() {}

	/** Starts the bot's bridge on a free local port. Call before scripts load. */
	static void start(Path work, ScriptThread scriptThread) throws IOException {
		workDirectory = work;
		try (ServerSocket probe = new ServerSocket(0)) {
			port = probe.getLocalPort();
		}
		Path saved = work.resolve(".skord").resolve("servers");
		if (java.nio.file.Files.isDirectory(saved)) {
			io.github.itsnotethann.skord.bridge.LinkStore seeding =
				new io.github.itsnotethann.skord.bridge.LinkStore(work.resolve(".skord").resolve("links.yml"));
			try (var files = java.nio.file.Files.list(saved)) {
				for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
					String name = file.getFileName().toString().replaceAll("\\.json$", "");
					if (!seeding.exists(name))
						seeding.create(name);
				}
			}
		}
		host = BridgeHost.start(work, new BridgeHost.Settings(port, "127.0.0.1", TIMEOUT_MILLIS, HELD_MAX_EVENTS,
			BridgeHost.Settings.DEFAULT_HELD_MAX_AGE_MILLIS, List.of("b")),
			() -> new Frame.BotInfo("1", "TestBot"), scriptThread::execute);
		if (host.server() == null)
			throw new IllegalStateException("the test bridge did not start");
	}

	static void registerSlowFunction() {
		io.github.itsnotethann.skord.shared.SharedFunctions.register(
			new Frame.FunctionSpec("slowAnswer", List.of(), "text"), args -> {
				CompletableFuture<com.google.gson.JsonElement> later = new CompletableFuture<>();
				TIMERS.schedule(() -> later.complete(new com.google.gson.JsonPrimitive("slow")), 600, TimeUnit.MILLISECONDS);
				return later;
			});
	}

	static Path workDir() {
		return workDirectory;
	}

	static String url() {
		return "ws://127.0.0.1:" + port;
	}

	static BridgeRouter router() {
		return host.router();
	}

	/** A fresh link for a server process; its secret goes on that process's command line. */
	static String createLink(String name, @org.jetbrains.annotations.Nullable String allow,
							 @org.jetbrains.annotations.Nullable String listen) throws IOException {
		host.links().revoke(name);
		var scope = allow == null ? java.util.EnumSet.noneOf(io.github.itsnotethann.skord.protocol.Scope.class)
			: io.github.itsnotethann.skord.protocol.Scope.parse(allow).scopes();
		var listens = listen == null ? java.util.EnumSet.noneOf(io.github.itsnotethann.skord.protocol.Listen.class)
			: io.github.itsnotethann.skord.protocol.Listen.parse(listen).listens();
		return host.links().create(name, scope, listens);
	}

	/** Subscribes a fake server (a plain bridge, no Skript) to raw event specs: {@code kind} or {@code kind:channel=id}. */
	static void subscribe(String name, String[] specs) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			throw new IllegalStateException("fake server '" + name + "' isn't connected");
		List<Frame.EventSpec> events = new ArrayList<>();
		for (String spec : specs) {
			String[] parts = spec.split(":", 2);
			com.google.gson.JsonObject filters = new com.google.gson.JsonObject();
			if (parts.length > 1) {
				for (String filter : parts[1].split(",")) {
					String[] pair = filter.split("=", 2);
					filters.addProperty(pair[0], pair[1]);
				}
			}
			events.add(new Frame.EventSpec(parts[0], filters, false));
		}
		server.bridge.subscribe(events);
	}

	static List<String> subscriptionRefusals(String name) {
		FakeServer server = SERVERS.get(name);
		return server == null ? List.of() : List.copyOf(server.refusals);
	}

	private static final class FakeServer {

		final String name;
		final Map<String, Command> staged = new LinkedHashMap<>();
		final List<String> rejected = new CopyOnWriteArrayList<>();
		final List<String> refusals = new CopyOnWriteArrayList<>();
		final java.util.Set<String> roster = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
		volatile Bridge bridge;

		FakeServer(String name) {
			this.name = name;
		}
	}

	private static final class Platform implements BridgePlatform {

		@Override
		public String name() {
			return "testbot";
		}

		@Override
		public Path dataDirectory() {
			return Path.of(".");
		}

		@Override
		public void runOnMain(Runnable task) {
			task.run();
		}

		@Override
		public void log(Level level, String message) {
			System.out.println("FAKE SERVER " + level + " " + message);
		}
	}

	/** Connects a server called {@code name}, creating its link first if it has none. Completes once welcomed. */
	static CompletionStage<Void> connect(String name) {
		return connect(name, null);
	}

	static CompletionStage<Void> connect(String name, @org.jetbrains.annotations.Nullable String scope) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		CompletableFuture<Void> connected = new CompletableFuture<>();
		try {
			// A secret can't be read back, so a server that already has a link gets a fresh one.
			host.links().revoke(name);
			java.util.Set<io.github.itsnotethann.skord.protocol.Scope> scopes = scope == null
				? java.util.EnumSet.noneOf(io.github.itsnotethann.skord.protocol.Scope.class)
				: io.github.itsnotethann.skord.protocol.Scope.parse(scope).scopes();
			String secret = host.links().create(name, scopes);
			BridgeConfig config = BridgeConfig.of(Map.of("url", "ws://127.0.0.1:" + port, "name", name,
				"secret", secret, "timeout", "2"));
			Bridge bridge = Bridge.start(config, new Platform());
			synchronized (server.roster) {
				server.roster.forEach(player -> bridge.playerJoined(uuidOf(player), player));
			}
			bridge.addListener(new Bridge.ConnectionListener() {
				@Override
				public void onConnect(Frame.BotInfo bot) {
					connected.complete(null);
				}

				@Override
				public void onSubscriptionsRejected(List<Frame.SubscribeResult.Rejection> rejected) {
					rejected.forEach(rejection -> server.refusals.add(rejection.kind() + ": " + rejection.reason()));
				}

				@Override
				public void onCommandsRejected(List<Frame.CommandsResult.Rejection> rejected) {
					rejected.forEach(rejection -> server.rejected.add(rejection.command() + ": " + rejection.reason()));
				}
			});
			server.bridge = bridge;
		} catch (IOException | RuntimeException e) {
			connected.completeExceptionally(e);
		}
		return connected.orTimeout(5, TimeUnit.SECONDS);
	}

	/** Closes a server's connection and completes once the bot has noticed. */
	static CompletionStage<Void> disconnect(String name) {
		FakeServer server = SERVERS.get(name);
		if (server != null && server.bridge != null)
			server.bridge.close();
		return until(() -> !host.server().isConnected(name), "the bot to notice '" + name + "' leaving");
	}

	/** Stages a command on a server. Nothing is sent until {@link #publish}. */
	static void declare(String name, String command, String arguments, String behaviour) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		SpecParser.Result<List<String>> path = SpecParser.path(command);
		if (path.failed())
			throw new IllegalArgumentException(path.error());
		SpecParser.Arguments parsed = new SpecParser.Arguments();
		if (arguments != null && !arguments.isBlank()) {
			for (String part : arguments.split(";")) {
				SpecParser.Result<CommandSpec.Option> option = SpecParser.option(part);
				String error = option.failed() ? option.error() : parsed.add(option.value());
				if (error != null)
					throw new IllegalArgumentException(error);
			}
		}
		CommandSpec spec = new CommandSpec(path.value(), command, java.util.EnumSet.noneOf(DiscordPermission.class),
			false, parsed.options(), "fake.sk", 0, false);
		server.staged.put(spec.name(), new Command(spec, handler(behaviour)));
	}

	/**
	 * Sends the staged set and completes once the bot has either registered every command or rejected it, so the
	 * script's next step sees the result.
	 */
	static CompletionStage<Void> publish(String name) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			return CompletableFuture.failedFuture(new IllegalStateException("fake server '" + name + "' isn't connected"));
		server.rejected.clear();
		server.bridge.declareCommands(new ArrayList<>(server.staged.values()));
		return until(() -> server.staged.keySet().stream().allMatch(command ->
				CommandRegistry.owners(command).contains(name)
					|| server.rejected.stream().anyMatch(line -> line.startsWith(command + ":"))),
			"the bot to take " + name + "'s commands");
	}

	static List<String> rejections(String name) {
		FakeServer server = SERVERS.get(name);
		return server == null ? List.of() : List.copyOf(server.rejected);
	}

	private static com.google.gson.JsonArray argumentsOf(@org.jetbrains.annotations.Nullable String json) {
		if (json == null || json.isBlank())
			return new com.google.gson.JsonArray();
		return com.google.gson.JsonParser.parseString(json).getAsJsonArray();
	}

	private static String show(com.google.gson.JsonElement data) {
		return data.isJsonPrimitive() ? data.getAsString() : data.toString();
	}

	static boolean sendCall(String name, String function, @org.jetbrains.annotations.Nullable String json) {
		FakeServer server = SERVERS.get(name);
		return server != null && server.bridge != null && server.bridge.send(null, function, argumentsOf(json));
	}

	static void record(String name, String function, String script) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		server.bridge.onCall(function, data -> {
			TestBot.recorder().mark(script, name + " got call " + function + ": " + show(data));
			return CompletableFuture.completedFuture(com.google.gson.JsonNull.INSTANCE);
		});
	}

	static void offer(String name, String function, String answer) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		server.bridge.onCall(function, ignored -> switch (answer) {
			case "!hang" -> new CompletableFuture<>();
			case "!fail" -> CompletableFuture.failedFuture(new IllegalStateException("fake server refusal"));
			default -> CompletableFuture.completedFuture(com.google.gson.JsonParser.parseString(answer));
		});
	}

	static CompletionStage<Void> declareFunctions(String name, String names) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			return CompletableFuture.failedFuture(new IllegalStateException("fake server '" + name + "' isn't connected"));
		List<Frame.FunctionSpec> specs = new ArrayList<>();
		for (String function : names.split(","))
			specs.add(new Frame.FunctionSpec(function.trim(), List.of(), null));
		server.bridge.declareFunctions(specs);
		return until(() -> host.router().calls().functionsOf(name) != null
			&& host.router().calls().functionsOf(name).size() == specs.size(), "the bot to take " + name + "'s functions");
	}

	static CompletionStage<io.github.itsnotethann.skord.rest.KeyedResult> call(String name, @org.jetbrains.annotations.Nullable String target, String function,
			@org.jetbrains.annotations.Nullable String json) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			return CompletableFuture.failedFuture(new IllegalStateException("fake server '" + name + "' isn't connected"));
		return server.bridge.call(target, function, argumentsOf(json)).handle((answer, error) -> {
			if (error == null)
				return io.github.itsnotethann.skord.shared.SharedValues.toSkript(answer);
			Throwable cause = io.github.itsnotethann.skord.remote.ServerCalls.translate(error);
			throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
		});
	}

	static List<String> linkCommand(String line) {
		return host.link(line.trim().split("\\s+"));
	}

	static void playerChange(String name, String player, boolean joined) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		if (joined)
			server.roster.add(player);
		else
			server.roster.remove(player);
		Bridge bridge = server.bridge;
		if (bridge == null)
			return;
		if (joined)
			bridge.playerJoined(uuidOf(player), player);
		else
			bridge.playerLeft(uuidOf(player), player);
	}

	static String uuidOf(String player) {
		return java.util.UUID.nameUUIDFromBytes(player.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
	}

	static void linkPlayer(String discordId, String player) {
		host.router().playerLinks().link(discordId, uuidOf(player));
	}

	static List<String> knownPlayers(String name) {
		return host.router().players(name);
	}

	static List<String> grantedScope(String name) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			return List.of();
		return server.bridge.scope().stream().sorted().map(item -> item.wire).toList();
	}

	/** As if the bot had just started: server-owned commands forgotten, then loaded again from disk. */
	static void restartBot() {
		BridgeRouter router = host.router();
		router.reloadFromDisk(host.links().list().stream().map(link -> link.name()).toList());
		CommandSync.request();
	}

	// "reply:Hello {name}!", "reply-ephemeral:...", "defer:...", "silent", "hang", "fail", "block:1500"
	private static CommandHandler handler(String behaviour) {
		int colon = behaviour.indexOf(':');
		String kind = colon < 0 ? behaviour : behaviour.substring(0, colon);
		String text = colon < 0 ? "" : behaviour.substring(colon + 1);
		return switch (kind) {
			case "reply" -> context -> {
				context.reply(fill(text, context), false);
				return null;
			};
			case "reply-ephemeral" -> context -> {
				context.reply(fill(text, context), true);
				return null;
			};
			case "defer" -> context -> context.defer(false).thenCompose(done -> {
				CompletableFuture<String> later = new CompletableFuture<>();
				TIMERS.schedule(() -> context.reply(fill(text, context), false).whenComplete((id, error) -> later.complete(id)),
					200, TimeUnit.MILLISECONDS);
				return later;
			});
			case "silent" -> context -> null;
			case "hang" -> context -> new CompletableFuture<>();
			case "fail" -> context -> {
				throw new IllegalStateException("fake server failure");
			};
			case "block" -> context -> {
				try {
					Thread.sleep(Long.parseLong(text));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				context.reply("too late", false);
				return null;
			};
			default -> throw new IllegalArgumentException("unknown fake behaviour '" + behaviour + "'");
		};
	}

	private static String fill(String text, CommandContext context) {
		String out = text;
		for (Map.Entry<String, Object> option : context.options().entrySet())
			out = out.replace("{" + option.getKey() + "}", String.valueOf(option.getValue()));
		return out;
	}

	private static CompletionStage<Void> until(BooleanSupplier condition, String what) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		long deadline = System.currentTimeMillis() + 5_000;
		Runnable[] poll = new Runnable[1];
		poll[0] = () -> {
			if (condition.getAsBoolean())
				done.complete(null);
			else if (System.currentTimeMillis() > deadline)
				done.completeExceptionally(new IllegalStateException("timed out waiting for " + what));
			else
				TIMERS.schedule(poll[0], 20, TimeUnit.MILLISECONDS);
		};
		TIMERS.execute(poll[0]);
		return done;
	}
}
