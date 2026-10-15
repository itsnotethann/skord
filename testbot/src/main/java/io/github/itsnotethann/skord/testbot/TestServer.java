package io.github.itsnotethann.skord.testbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.ScriptThread;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Listen;
import io.github.itsnotethann.skord.protocol.Scope;
import io.github.itsnotethann.skord.remote.RemoteServer;
import io.github.itsnotethann.skord.syntax.SkordSyntax;
import org.skriptlang.skript.platform.Platform;

/**
 * A game server that runs Skript, as its own process: the shared syntax over {@code RemoteDiscord}, connected to
 * the bot's bridge over a real local WebSocket. One JVM cannot hold two script engines (the engine, its
 * registries and {@code CommandRegistry} are static), so testbot starts this as a child of the bot.
 * <p>
 * Arguments: url, server name, secret, scripts folder, work folder. It prints {@code MARK <script>: <text>} for
 * each mark, {@code CTRL <word>} for progress, and reads {@code reload} and {@code quit} from stdin.
 */
public final class TestServer {

	private TestServer() {}

	public static void main(String[] args) throws Exception {
		URI url = URI.create(args[0]);
		String name = args[1];
		String secret = args[2];
		Path scripts = Path.of(args[3]);
		Path work = Path.of(args[4]);
		if (args.length > 5 && !args[5].equals("-"))
			scripts = scripts.resolve(args[5]);
		System.setProperty("skord.testserver", "true");
		String primary = stage(scripts, work);

		ScriptThread scriptThread = new ScriptThread();
		ServerBus bus = new ServerBus(scriptThread);
		Platform.install(new TestBot(work.toFile(), scriptThread, bus));
		BridgePlatform platform = new BridgePlatform() {
			@Override
			public String name() {
				return "testserver";
			}

			@Override
			public Path dataDirectory() {
				return work;
			}

			@Override
			public void runOnMain(Runnable task) {
				scriptThread.execute(task);
			}

			@Override
			public void log(Level level, String message) {
				System.out.println("LOG " + level + " " + message);
			}
		};
		RemoteServer server = new RemoteServer(platform);
		BridgeConfig config = new BridgeConfig(url, name, secret, 2_000);
		Bridge.ConnectionListener progress = new Bridge.ConnectionListener() {
			@Override
			public void onConnect(Frame.BotInfo bot) {
				System.out.println("CTRL connected");
			}

			@Override
			public void onSubscriptionsRejected(List<Frame.SubscribeResult.Rejection> rejected) {
				for (Frame.SubscribeResult.Rejection rejection : rejected)
					TestBot.recorder().mark(primary, "refused " + rejection.kind() + ": " + rejection.reason());
			}

			@Override
			public void onCommandsRejected(List<Frame.CommandsResult.Rejection> rejected) {
				for (Frame.CommandsResult.Rejection rejection : rejected)
					TestBot.recorder().mark(primary, "rejected /" + rejection.command() + ": " + rejection.reason());
			}

			@Override
			public void onScopeChanged(Set<Scope> scope, Set<Listen> listen) {
				TestBot.recorder().mark(primary, "scope now: "
					+ scope.stream().sorted().map(item -> item.wire).collect(java.util.stream.Collectors.joining(","))
					+ "; listen: " + (listen.isEmpty() ? "none" : listen.stream().sorted().map(item -> item.wire)
					.collect(java.util.stream.Collectors.joining(","))));
			}
		};

		Throwable[] failure = new Throwable[1];
		java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
		scriptThread.execute(() -> {
			try {
				Skript skript = new Skript();
				Skript.onRegistration(() -> {
					SkordSyntax.register();
					TestSyntax.register();
				});
				Skript.onFinishedLoading(() -> scriptThread.execute(() -> {
					server.start(config, progress);
					server.publish();
					System.out.println("CTRL loaded");
					loaded.countDown();
				}));
				skript.setEnabled(true);
				skript.onEnable();
				Skript.closeUnsafeSkript();
			} catch (Throwable throwable) {
				failure[0] = throwable;
				loaded.countDown();
			}
		});
		loaded.await();
		if (failure[0] != null) {
			failure[0].printStackTrace(System.out);
			System.exit(2);
		}

		try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.equals("reload")) {
					scriptThread.execute(() -> {
						server.publish();
						System.out.println("CTRL reloaded");
					});
				} else if (line.equals("quit")) {
				break;
				} else if (line.startsWith("release ")) {
				TestHeldSyntax.release(line.substring(8).trim());
				} else if (line.startsWith("join ") || line.startsWith("leave ")) {
				boolean joined = line.startsWith("join ");
				String player = line.substring(line.indexOf(' ') + 1).trim();
				Bridge live = server.bridge();
				if (live != null) {
					String uuid = java.util.UUID.nameUUIDFromBytes(player.getBytes(StandardCharsets.UTF_8)).toString();
					if (joined)
						live.playerJoined(uuid, player);
					else
						live.playerLeft(uuid, player);
				}
				}
			}
		}
		System.exit(0);
	}

	private static String stage(Path source, Path work) throws IOException {
		Path target = work.resolve("Skript").resolve("scripts");
		if (Files.exists(work)) {
			try (Stream<Path> paths = Files.walk(work)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
		Files.createDirectories(target);
		try (Stream<Path> files = Files.list(source)) {
			String first = null;
			for (Path script : files.filter(path -> path.getFileName().toString().endsWith(".sk")).sorted().toList()) {
				Files.copy(script, target.resolve(script.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				if (first == null)
					first = script.getFileName().toString();
			}
			return first == null ? "remote.sk" : first;
			}
	}
}
