package io.github.itsnotethann.skord.testbot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import io.github.itsnotethann.skord.bridge.BridgeRouter;
import org.jetbrains.annotations.Nullable;

/**
 * The parent's end of a {@link TestServer} child: starts it, relays its marks into the bot's recorder under
 * {@code server/<script>}, and waits for the bot to have received what the child sent.
 */
final class ServerProcess {

	private static final Map<String, ServerProcess> RUNNING = new ConcurrentHashMap<>();
	private static final Map<String, String> SECRETS = new ConcurrentHashMap<>();
	private static volatile @Nullable Path scripts;

	private final String name;
	private final Process process;
	private final BufferedWriter stdin;

	private ServerProcess(String name, Process process) {
		this.name = name;
		this.process = process;
		this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
	}

	static void scripts(@Nullable Path directory) {
		scripts = directory;
	}

	/** Links {@code name}, starts its process, and completes once the bot has its commands and subscriptions. */
	static CompletionStage<Void> start(String name, @Nullable String allow, @Nullable String listen) {
		return start(name, allow, listen, null);
	}

	static CompletionStage<Void> start(String name, @Nullable String allow, @Nullable String listen,
									   @Nullable String profile) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		Path directory = scripts;
		if (directory == null) {
			done.completeExceptionally(new IllegalStateException("no server scripts folder was given to testbot"));
			return done;
		}
		CompletableFuture.runAsync(() -> {
			try {
				String secret = SECRETS.get(name);
				if (secret == null) {
					secret = TestBridge.createLink(name, allow, listen);
					SECRETS.put(name, secret);
				}
				BridgeRouter router = TestBridge.router();
				int commandsBefore = router.receivedFrames(name, "commands");
				int subscribesBefore = router.receivedFrames(name, "subscribe");
				String java = ProcessHandle.current().info().command()
					.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
				Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
					TestServer.class.getName(), TestBridge.url(), name, secret, directory.toAbsolutePath().toString(),
					TestBridge.workDir().resolveSibling(TestBridge.workDir().getFileName() + "-server-" + name)
						.toAbsolutePath().toString(), profile == null ? "-" : profile)
						.redirectErrorStream(true).start();
				ServerProcess child = new ServerProcess(name, process);
				RUNNING.put(name, child);
				Thread reader = new Thread(child::relay, "testbot-server-" + name);
				reader.setDaemon(true);
				reader.start();
				waitFor(() -> router.receivedFrames(name, "commands") > commandsBefore
				&& router.receivedFrames(name, "subscribe") > subscribesBefore, "the bot to get " + name + "'s commands "
				+ "and subscriptions", process);
				done.complete(null);
			} catch (Exception e) {
				done.completeExceptionally(e);
			}
		});
		return done;
	}

	/** Has the child declare its commands and events again, as after a reload; completes once the bot has them. */
	static CompletionStage<Void> reload(String name) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		ServerProcess child = RUNNING.get(name);
		if (child == null) {
			done.completeExceptionally(new IllegalStateException("no server process '" + name + "'"));
			return done;
		}
		CompletableFuture.runAsync(() -> {
			try {
				BridgeRouter router = TestBridge.router();
				int commands = router.receivedFrames(name, "commands");
				int subscribes = router.receivedFrames(name, "subscribe");
				child.stdin.write("reload\n");
				child.stdin.flush();
				waitFor(() -> router.receivedFrames(name, "commands") > commands
					&& router.receivedFrames(name, "subscribe") > subscribes, "the bot to get " + name + "'s reload",
					child.process);
				done.complete(null);
			} catch (Exception e) {
				done.completeExceptionally(e);
			}
		});
		return done;
	}

	static CompletionStage<Void> kill(String name) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		ServerProcess child = RUNNING.remove(name);
		if (child == null) {
			done.completeExceptionally(new IllegalStateException("no server process '" + name + "'"));
			return done;
		}
		CompletableFuture.runAsync(() -> {
			try {
				child.process.destroyForcibly();
				child.process.waitFor();
				waitFor(() -> !TestBridge.router().isOnline(name), "the bot to notice " + name + " dying",
					child.process, true);
				done.complete(null);
			} catch (Exception e) {
				done.completeExceptionally(e);
			}
		});
		return done;
	}

	static void send(String name, String line) {
		ServerProcess child = RUNNING.get(name);
		if (child == null)
			throw new IllegalStateException("no server process '" + name + "'");
		try {
			child.stdin.write(line + "\n");
			child.stdin.flush();
		} catch (IOException e) {
			throw new IllegalStateException("could not reach server process '" + name + "': " + e.getMessage());
		}
	}

	static void stopAll() {
		for (ServerProcess child : RUNNING.values()) {
			try {
				child.stdin.write("quit\n");
				child.stdin.flush();
			} catch (IOException ignored) {
			}
			child.process.destroy();
		}
		RUNNING.clear();
	}

	private void relay() {
		try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = out.readLine()) != null) {
				if (line.startsWith("MARK ")) {
					int colon = line.indexOf(": ");
					if (colon > 5) {
						TestBot.recorder().mark("server/" + line.substring(5, colon), line.substring(colon + 2));
						continue;
					}
				}
				System.out.println("SERVER " + name + ": " + line);
			}
		} catch (IOException ignored) {
		}
	}

	private static void waitFor(BooleanSupplier condition, String what, Process process) throws Exception {
		waitFor(condition, what, process, false);
	}

	private static void waitFor(BooleanSupplier condition, String what, Process process, boolean allowDead)
			throws Exception {
	long deadline = System.currentTimeMillis() + 20_000;
	while (!condition.getAsBoolean()) {
	if (!allowDead && !process.isAlive())
				throw new IllegalStateException("the server process died before " + what);
			if (System.currentTimeMillis() > deadline)
				throw new IllegalStateException("timed out waiting for " + what);
			Thread.sleep(25);
		}
	}

	static List<String> running() {
		return List.copyOf(RUNNING.keySet());
	}
}
