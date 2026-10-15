package io.github.itsnotethann.skord.testbot;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import io.github.itsnotethann.skord.bridge.BridgeServer;
import io.github.itsnotethann.skord.bridge.LinkCommands;
import io.github.itsnotethann.skord.bridge.LinkStore;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.protocol.BridgeClient;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;

/**
 * The bridge against a fake game server over a real local WebSocket. No Skript: the bot's {@link BridgeServer}
 * and {@link LinkStore} on one side, the same {@link Bridge} a plugin would embed on the other.
 * <p>
 * Each check prints {@code ok} or {@code FAIL}; any failure makes the run exit non-zero.
 */
public final class BridgeTest {

	private static final Frame.BotInfo BOT = new Frame.BotInfo("42", "TestBot");

	private static final List<String> FAILURES = new ArrayList<>();

	/** Records what the bot side saw, in order. */
	private static final class Events implements BridgeServer.Listener {

		final List<String> seen = new CopyOnWriteArrayList<>();

		@Override
		public void connected(String server, String platform) {
			seen.add("connected " + server + " " + platform);
		}

		@Override
		public void disconnected(String server) {
			seen.add("disconnected " + server);
		}

		@Override
		public void frame(String server, Frame frame) {
			seen.add("frame " + server + " " + frame.type());
		}
	}

	/** A platform whose main thread is the calling thread, and which keeps what was logged. */
	private static final class FakePlatform implements BridgePlatform {

		final List<String> log = new CopyOnWriteArrayList<>();
		final Path data;

		FakePlatform(Path data) {
			this.data = data;
		}

		@Override
		public String name() {
			return "test";
		}

		@Override
		public Path dataDirectory() {
			return data;
		}

		@Override
		public void runOnMain(Runnable task) {
			task.run();
		}

		@Override
		public void log(Level level, String message) {
			log.add(level + " " + message);
		}
	}

	/** A listener that records what a client was told. */
	private static final class ClientEvents implements BridgeClient.Listener {

		final List<String> seen = new CopyOnWriteArrayList<>();

		@Override
		public void onWelcome(Frame.Welcome welcome) {
			seen.add("welcome " + welcome.bot().name());
		}

		@Override
		public void onDenied(Frame.Denied denied) {
			seen.add("denied " + denied.reason());
		}

		@Override
		public void onFrame(Frame frame) {
			seen.add("frame " + frame.type());
		}

		@Override
		public void onDisconnect(String reason) {
			seen.add("disconnect");
		}

		long count(String what) {
			return seen.stream().filter(what::equals).count();
		}
	}

	public static void main(String[] args) throws Exception {
		Path work = Files.createTempDirectory("skord-bridgetest");
		try {
			linkStore(work);
			linkCommands(work);
			handshake(work);
			refusals(work);
			replaced(work);
			revoked(work);
			reconnects(work);
			silentConnection(work);
		} finally {
			deleteTree(work);
		}
		if (FAILURES.isEmpty()) {
			System.out.println("BRIDGETEST PASS");
			System.exit(0);
		}
		FAILURES.forEach(failure -> System.out.println("BRIDGETEST FAIL: " + failure));
		System.exit(1);
	}

	private static void linkStore(Path work) throws Exception {
		System.out.println("-- link store");
		Path file = work.resolve("store").resolve("links.yml");
		LinkStore store = new LinkStore(file);
		String secret = store.create("survival");
		check(secret != null && secret.startsWith("skl_"), "create returns a secret");
		check(store.create("survival") == null, "a name can't be created twice");
		check(store.authenticate("survival", secret), "the secret authenticates its own link");
		check(!store.authenticate("survival", secret + "x"), "a wrong secret fails");
		check(!store.authenticate("nobody", secret), "an unknown name fails");
		String saved = Files.readString(file);
		check(!saved.contains(secret), "the secret is not written to links.yml");
		check(saved.contains("sha256"), "the hash is written");
		LinkStore reloaded = new LinkStore(file);
		check(reloaded.authenticate("survival", secret), "links survive a restart");
		check(reloaded.revoke("survival") && !reloaded.exists("survival"), "revoke removes a link");
		check(!new LinkStore(file).authenticate("survival", secret), "a revoked secret stays dead after a restart");
		check(!LinkStore.validName("Has Space") && LinkStore.validName("hub-2"), "names are validated");
	}

	private static void linkCommands(Path work) throws Exception {
		System.out.println("-- sk link");
		LinkStore store = new LinkStore(work.resolve("cmd").resolve("links.yml"));
		List<String> created = LinkCommands.run(store, null, name -> {}, new String[] {"create", "Hub"});
		check(created.stream().anyMatch(line -> line.contains("secret: skl_")), "create prints the secret");
		check(created.stream().anyMatch(line -> line.contains("name:   hub")), "names are lower-cased");
		check(LinkCommands.run(store, null, name -> {}, new String[] {"create", "hub"}).getFirst().contains("already exists"),
			"creating twice is refused");
		check(LinkCommands.run(store, null, name -> {}, new String[] {"list"}).stream().anyMatch(line -> line.contains("hub")),
			"list shows the link");
		check(LinkCommands.run(store, null, name -> {}, new String[] {"revoke", "ghost"}).getFirst().contains("No link"),
			"revoking nothing says so");
		check(LinkCommands.run(store, null, name -> {}, new String[] {"create", "bad name"}).getFirst().contains("isn't a valid"),
			"bad names are refused");
		check(LinkCommands.run(store, null, name -> {}, new String[] {}).getFirst().startsWith("Usage"), "no arguments shows usage");
		BridgeConfig config = new BridgeConfig(URI.create("ws://localhost:1"), "hub", "skl_topsecret", 5000);
		check(!config.toString().contains("topsecret"), "BridgeConfig never prints the secret");
		check(!new Frame.Hello(1, "hub", "skl_topsecret", "test").toString().contains("topsecret"),
			"Hello never prints the secret");
	}

	private static void handshake(Path work) throws Exception {
		System.out.println("-- handshake");
		LinkStore store = new LinkStore(work.resolve("hs").resolve("links.yml"));
		String secret = store.create("survival");
		Events events = new Events();
		BridgeServer server = start(store, events);
		try {
			FakePlatform platform = new FakePlatform(work);
			Bridge bridge = Bridge.start(config(server, "survival", secret), platform);
			CountDownLatch connected = new CountDownLatch(1);
			bridge.addListener(new Bridge.ConnectionListener() {
				@Override
				public void onConnect(Frame.BotInfo bot) {
					connected.countDown();
				}
			});
			await(() -> bridge.connected(), "the bridge connects");
			check(bridge.bot() != null && "TestBot".equals(bridge.bot().name()), "welcome names the bot");
			check(server.isConnected("survival"), "the bot sees the server connected");
			await(() -> events.seen.contains("connected survival test"), "the bot's listener hears the hello");
			check(!platform.log.toString().contains(secret), "the secret is never logged");
			bridge.close();
			await(() -> events.seen.contains("disconnected survival"), "the bot hears the disconnect");
			check(!server.isConnected("survival"), "a closed server is no longer connected");
		} finally {
			server.shutdown();
		}
	}

	private static void refusals(Path work) throws Exception {
		System.out.println("-- refusals");
		LinkStore store = new LinkStore(work.resolve("ref").resolve("links.yml"));
		String secret = store.create("survival");
		BridgeServer server = start(store, new Events());
		try {
			URI uri = uri(server);
			ClientEvents wrongSecret = new ClientEvents();
			BridgeClient a = new BridgeClient(uri, () -> hello("survival", "skl_wrong", 1), wrongSecret, 200);
			a.start();
			await(() -> wrongSecret.count("denied bad-secret") == 1, "a wrong secret is denied");

			ClientEvents unknown = new ClientEvents();
			BridgeClient b = new BridgeClient(uri, () -> hello("ghost", secret, 1), unknown, 200);
			b.start();
			await(() -> unknown.count("denied bad-secret") == 1, "an unknown name gets the same denial");

			ClientEvents version = new ClientEvents();
			BridgeClient c = new BridgeClient(uri, () -> hello("survival", secret, 99), version, 200);
			c.start();
			await(() -> version.count("denied version") == 1, "a different protocol version is denied");

			Thread.sleep(1200); // a denied client must not come back
			check(wrongSecret.count("denied bad-secret") == 1 && version.count("denied version") == 1,
				"a denied client does not retry");
			check(!server.isConnected("survival"), "none of them got in");
			a.close();
			b.close();
			c.close();

			// A first frame that isn't hello, and one that isn't JSON.
			ClientEvents junk = new ClientEvents();
			CountDownLatch closed = new CountDownLatch(1);
			WebSocket raw = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
				@Override
				public void onOpen(WebSocket webSocket) {
					webSocket.request(2);
				}

				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					junk.seen.add(String.valueOf(data));
					return null;
				}

				@Override
				public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
					closed.countDown();
					return null;
				}
			}).join();
			raw.sendText("this is not json", true);
			check(closed.await(5, TimeUnit.SECONDS), "garbage before hello closes the connection");
			check(junk.seen.stream().anyMatch(text -> text.contains("\"protocol\"")), "and says why");
		} finally {
			server.shutdown();
		}
	}

	private static void replaced(Path work) throws Exception {
		System.out.println("-- replace");
		LinkStore store = new LinkStore(work.resolve("rep").resolve("links.yml"));
		String secret = store.create("survival");
		Events events = new Events();
		BridgeServer server = start(store, events);
		try {
			ClientEvents first = new ClientEvents();
			BridgeClient one = new BridgeClient(uri(server), () -> hello("survival", secret, 1), first, 200);
			one.start();
			await(() -> first.count("welcome TestBot") == 1, "the first connects");
			ClientEvents second = new ClientEvents();
			BridgeClient two = new BridgeClient(uri(server), () -> hello("survival", secret, 1), second, 200);
			two.start();
			await(() -> second.count("welcome TestBot") == 1, "the second connects");
			await(() -> first.count("denied replaced") == 1, "the older one is told it was replaced");
			Thread.sleep(800);
			check(server.isConnected("survival"), "the newer connection is the live one");
			check(events.seen.stream().filter("disconnected survival"::equals).count() == 0,
				"replacing is not reported as a disconnect");
			one.close();
			two.close();
		} finally {
			server.shutdown();
		}
	}

	private static void revoked(Path work) throws Exception {
		System.out.println("-- revoke while connected");
		LinkStore store = new LinkStore(work.resolve("rev").resolve("links.yml"));
		String secret = store.create("survival");
		Events events = new Events();
		BridgeServer server = start(store, events);
		try {
			ClientEvents client = new ClientEvents();
			BridgeClient one = new BridgeClient(uri(server), () -> hello("survival", secret, 1), client, 200);
			one.start();
			await(() -> client.count("welcome TestBot") == 1, "connects");
			LinkCommands.run(store, server, name -> {}, new String[] {"revoke", "survival"});
			await(() -> client.count("denied revoked") == 1, "a revoked server is cut off and told why");
			await(() -> events.seen.contains("disconnected survival"), "the bot records the disconnect");
			one.close();
		} finally {
			server.shutdown();
		}
	}

	private static void reconnects(Path work) throws Exception {
		System.out.println("-- reconnect");
		LinkStore store = new LinkStore(work.resolve("re").resolve("links.yml"));
		String secret = store.create("survival");
		BridgeServer first = start(store, new Events());
		int port = first.port();
		ClientEvents client = new ClientEvents();
		BridgeClient one = new BridgeClient(URI.create("ws://127.0.0.1:" + port), () -> hello("survival", secret, 1),
			client, 300);
		BridgeServer second = null;
		try {
			one.start();
			await(() -> client.count("welcome TestBot") == 1, "connects to the first bot");
			first.shutdown();
			await(() -> client.count("disconnect") >= 1, "notices the bot going away");
			second = new BridgeServer(new InetSocketAddress("127.0.0.1", port), store, () -> BOT, new Events());
			second.startAndWait();
			await(() -> client.count("welcome TestBot") == 2, "reconnects by itself once the bot is back");
			check(second.isConnected("survival"), "the restarted bot sees the server again");
		} finally {
			one.close();
			if (second != null)
				second.shutdown();
		}
	}

	private static void silentConnection(Path work) throws Exception {
		System.out.println("-- no hello");
		LinkStore store = new LinkStore(work.resolve("silent").resolve("links.yml"));
		BridgeServer server = start(store, new Events());
		try {
			CountDownLatch closed = new CountDownLatch(1);
			HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri(server), new WebSocket.Listener() {
				@Override
				public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
					closed.countDown();
					return null;
				}
			}).join();
			check(closed.await(10, TimeUnit.SECONDS), "a connection that never says hello is dropped");
		} finally {
			server.shutdown();
		}
	}

	private static Frame.Hello hello(String server, String secret, int version) {
		return new Frame.Hello(version, server, secret, "test");
	}

	private static BridgeServer start(LinkStore store, BridgeServer.Listener listener) throws Exception {
		BridgeServer server = new BridgeServer(new InetSocketAddress("127.0.0.1", 0), store, () -> BOT, listener);
		server.startAndWait();
		return server;
	}

	private static URI uri(BridgeServer server) {
		return URI.create("ws://127.0.0.1:" + server.port());
	}

	private static BridgeConfig config(BridgeServer server, String name, String secret) {
		return BridgeConfig.of(Map.of("url", uri(server).toString(), "name", name, "secret", secret));
	}

	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				check(true, what);
				return;
			}
			Thread.sleep(25);
		}
		check(false, what + " (timed out)");
	}

	private static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok)
			FAILURES.add(what);
	}

	private static void deleteTree(Path root) throws java.io.IOException {
		if (!Files.exists(root))
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
