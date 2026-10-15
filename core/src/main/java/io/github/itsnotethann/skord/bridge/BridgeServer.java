package io.github.itsnotethann.skord.bridge;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot's end of the bridge: game servers connect here, say hello with their secret, and stay connected.
 * The WebSocket library stays an implementation detail of this class.
 * <p>
 * Callbacks run on the library's threads. {@link Listener} implementations hop to the script thread themselves
 * before touching any script state.
 */
public final class BridgeServer {

	public interface Listener {

		void connected(String server, String platform);

		void disconnected(String server);

		void frame(String server, Frame frame);
	}

	/** How long a new connection has to say hello before it's dropped. */
	static final long HELLO_TIMEOUT_MILLIS = 5_000;
	static final long HEARTBEAT_MILLIS = 15_000;

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private static final class Session {
		volatile @Nullable String server;
	}

	private final LinkStore links;
	private final Supplier<Frame.BotInfo> bot;
	private final Listener listener;
	private final Socket socket;
	private final Map<String, WebSocket> connections = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "skord-bridge-timers");
		thread.setDaemon(true);
		return thread;
	});
	private final CountDownLatch started = new CountDownLatch(1);
	private volatile @Nullable Exception startFailure;

	public BridgeServer(InetSocketAddress address, LinkStore links, Supplier<Frame.BotInfo> bot, Listener listener) {
		this.links = links;
		this.bot = bot;
		this.listener = listener;
		this.socket = new Socket(address);
	}

	/** Starts listening and waits until the port is bound. Throws if it can't be. */
	public void startAndWait() throws Exception {
		socket.start();
		if (!started.await(10, TimeUnit.SECONDS))
			throw new IllegalStateException("the bridge did not start listening within 10 seconds");
		Exception failure = startFailure;
		if (failure != null)
			throw failure;
	}

	/** The port actually bound, which differs from the requested one only when that was 0. */
	public int port() {
		return socket.getPort();
	}

	public boolean isConnected(String server) {
		return connections.containsKey(server);
	}

	public List<String> connectedServers() {
		return connections.keySet().stream().sorted().toList();
	}

	/** Sends a frame to a connected server. Returns false if it isn't connected. */
	public boolean send(String server, Frame frame) {
		WebSocket connection = connections.get(server);
		if (connection == null || !connection.isOpen())
			return false;
		connection.send(FrameCodec.encode(frame));
		return true;
	}

	/** Tells a connected server what its link allows now, so its own view stays current. */
	public void pushScope(String server) {
		send(server, new Frame.ScopeUpdate(links.scope(server), links.listen(server)));
	}

	/** Cuts a server off, telling it why. Used when its link is revoked. */
	public void drop(String server, String reason) {
		WebSocket connection = connections.get(server);
		if (connection != null)
			refuse(connection, reason);
	}

	/** Stops listening and closes every connection. */
	public void shutdown() {
		timers.shutdownNow();
		try {
			socket.stop(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void greet(WebSocket connection, Session session, Frame.Hello hello) {
		if (hello.v() != FrameCodec.VERSION) {
			refuse(connection, Frame.Denied.VERSION);
			return;
		}
		// An unknown name and a wrong secret look the same from outside, so names can't be probed.
		if (!links.authenticate(hello.server(), hello.secret())) {
			LOGGER.warn("Bridge: refused a connection claiming to be '{}' (bad secret or no such link)", hello.server());
			refuse(connection, Frame.Denied.BAD_SECRET);
			return;
		}
		String server = hello.server();
		WebSocket previous = connections.put(server, connection);
		session.server = server;
		if (previous != null && previous != connection) {
			LOGGER.info("Bridge: '{}' connected again; closing the older connection", server);
			previous.send(FrameCodec.encode(new Frame.Denied(Frame.Denied.REPLACED)));
			previous.close(1000, "replaced");
		}
		connection.send(FrameCodec.encode(new Frame.Welcome(bot.get(), HEARTBEAT_MILLIS, links.scope(server))));
		LOGGER.info("Bridge: server '{}' connected ({})", server, hello.platform());
		listener.connected(server, hello.platform());
	}

	private void refuse(WebSocket connection, String reason) {
		if (connection.isOpen()) {
			connection.send(FrameCodec.encode(new Frame.Denied(reason)));
			connection.close(1008, reason);
		}
	}

	private final class Socket extends WebSocketServer {

		Socket(InetSocketAddress address) {
			super(address);
			setReuseAddr(true);
		}

		@Override
		public void onStart() {
			setConnectionLostTimeout((int) (HEARTBEAT_MILLIS * 2 / 1000));
			started.countDown();
		}

		@Override
		public void onOpen(WebSocket connection, ClientHandshake handshake) {
			Session session = new Session();
			connection.setAttachment(session);
			timers.schedule(() -> {
				if (session.server == null && connection.isOpen())
					connection.close(1008, "no hello");
			}, HELLO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		}

		@Override
		public void onMessage(WebSocket connection, String text) {
			Session session = connection.getAttachment();
			if (session == null)
				return;
			Frame frame;
			try {
				frame = FrameCodec.decode(text);
			} catch (FrameCodec.FrameException e) {
				refuse(connection, Frame.Denied.PROTOCOL);
				return;
			}
			String server = session.server;
			if (server == null) {
				if (frame instanceof Frame.Hello hello)
					greet(connection, session, hello);
				else
					refuse(connection, Frame.Denied.PROTOCOL);
				return;
			}
			listener.frame(server, frame);
		}

		@Override
		public void onClose(WebSocket connection, int code, String reason, boolean remote) {
			Session session = connection.getAttachment();
			String server = session == null ? null : session.server;
			if (server == null)
				return;
			// A replaced connection closes after its successor registered: only the current one counts as a drop.
			if (connections.remove(server, connection)) {
				LOGGER.info("Bridge: server '{}' disconnected", server);
				listener.disconnected(server);
			}
		}

		@Override
		public void onError(WebSocket connection, Exception error) {
			if (connection == null) {
				startFailure = error;
				started.countDown();
				return;
			}
			LOGGER.debug("Bridge: connection error", error);
		}
	}
}
