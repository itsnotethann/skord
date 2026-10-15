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

	private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testbot-bridge");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, FakeServer> SERVERS = new ConcurrentHashMap<>();

	private static volatile int port;
	private static volatile BridgeHost host;

	private TestBridge() {}

	/** Starts the bot's bridge on a free local port. Call before scripts load. */
	static void start(Path work, ScriptThread scriptThread) throws IOException {
		try (ServerSocket probe = new ServerSocket(0)) {
			port = probe.getLocalPort();
		}
		host = BridgeHost.start(work, new BridgeHost.Settings(port, "127.0.0.1", TIMEOUT_MILLIS),
			() -> new Frame.BotInfo("1", "TestBot"), scriptThread::execute);
		if (host.server() == null)
			throw new IllegalStateException("the test bridge did not start");
	}

	private static final class FakeServer {

		final String name;
		final Map<String, Command> staged = new LinkedHashMap<>();
		final List<String> rejected = new CopyOnWriteArrayList<>();
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
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		CompletableFuture<Void> connected = new CompletableFuture<>();
		try {
			// A secret can't be read back, so a server that already has a link gets a fresh one.
			host.links().revoke(name);
			String secret = host.links().create(name);
			BridgeConfig config = BridgeConfig.of(Map.of("url", "ws://127.0.0.1:" + port, "name", name,
				"secret", secret, "timeout", "2"));
			Bridge bridge = Bridge.start(config, new Platform());
			bridge.addListener(new Bridge.ConnectionListener() {
				@Override
				public void onConnect(Frame.BotInfo bot) {
					connected.complete(null);
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
				name.equals(CommandRegistry.owner(command))
					|| server.rejected.stream().anyMatch(line -> line.startsWith(command + ":"))),
			"the bot to take " + name + "'s commands");
	}

	static List<String> rejections(String name) {
		FakeServer server = SERVERS.get(name);
		return server == null ? List.of() : List.copyOf(server.rejected);
	}

	/**
	 * Test data as text: {@code "a=1;b=x"} is an object, {@code "x,y"} a list, anything else a string.
	 */
	private static com.google.gson.JsonElement dataOf(@org.jetbrains.annotations.Nullable String text) {
		if (text == null)
			return com.google.gson.JsonNull.INSTANCE;
		if (!text.contains("=") && text.contains(",")) {
			com.google.gson.JsonArray array = new com.google.gson.JsonArray();
			for (String part : text.split(","))
				array.add(part.trim());
			return array;
		}
		if (!text.contains("="))
			return new com.google.gson.JsonPrimitive(text);
		// "a=1;b::c=x": numbers are numbers, and "::" nests, as it does in a list variable.
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		for (String part : text.split(";")) {
			String[] pair = part.split("=", 2);
			String[] path = pair[0].trim().split("::");
			com.google.gson.JsonObject at = root;
			for (int i = 0; i < path.length - 1; i++) {
				if (!at.has(path[i]))
					at.add(path[i], new com.google.gson.JsonObject());
				at = at.getAsJsonObject(path[i]);
			}
			String value = pair.length > 1 ? pair[1].trim() : "";
			String last = path[path.length - 1];
			if (value.matches("-?\\d+"))
				at.addProperty(last, Long.parseLong(value));
			else if (value.matches("-?\\d+\\.\\d+"))
				at.addProperty(last, Double.parseDouble(value));
			else
				at.addProperty(last, value);
		}
		return root;
	}

	private static String show(com.google.gson.JsonElement data) {
		return data.isJsonPrimitive() ? data.getAsString() : data.toString();
	}

	static boolean sendMessage(String name, String message, @org.jetbrains.annotations.Nullable String data) {
		FakeServer server = SERVERS.get(name);
		return server != null && server.bridge != null && server.bridge.send(message, dataOf(data));
	}

	/** Records, under {@code script}, each message of this name the server receives. */
	static void listen(String name, String message, String script) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		server.bridge.onMessage(message, data ->
			TestBot.recorder().mark(script, name + " got message " + message + ": " + show(data)));
	}

	/** {@code "!hang"} never answers and {@code "!fail"} answers with an error; otherwise {@link #dataOf}. */
	static void answer(String name, String request, String data) {
		FakeServer server = SERVERS.computeIfAbsent(name, FakeServer::new);
		server.bridge.onRequest(request, ignored -> switch (data) {
			case "!hang" -> new CompletableFuture<>();
			case "!fail" -> CompletableFuture.failedFuture(new IllegalStateException("fake server refusal"));
			default -> CompletableFuture.completedFuture(dataOf(data));
		});
	}

	/** The fake server asks the bot; completes with the answer in the shape scripts store it. */
	static CompletionStage<io.github.itsnotethann.skord.rest.KeyedResult> ask(String name, String request,
			@org.jetbrains.annotations.Nullable String data) {
		FakeServer server = SERVERS.get(name);
		if (server == null || server.bridge == null)
			return CompletableFuture.failedFuture(new IllegalStateException("fake server '" + name + "' isn't connected"));
		return server.bridge.ask(request, dataOf(data))
			.handle((answer, error) -> {
				if (error == null)
					return io.github.itsnotethann.skord.bridge.BridgeData.toSkript(answer);
				Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
					? error.getCause() : error;
				if (cause instanceof io.github.itsnotethann.skord.bridge.api.BridgeRequestException failure)
					throw new io.github.itsnotethann.skord.bridge.BridgeFailure(failure.errorType(), failure.getMessage());
				throw new RuntimeException(cause);
			});
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
