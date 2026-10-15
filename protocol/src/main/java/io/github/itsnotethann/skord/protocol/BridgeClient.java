package io.github.itsnotethann.skord.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

/**
 * A game server's side of the WebSocket: connects out, says hello, and reconnects with backoff whenever the
 * connection drops. A {@code denied} is final: retrying a wrong secret would only hammer the bot, so the client
 * stops and reports it.
 * <p>
 * Callbacks run on the client's own threads. Consumers hop to their main thread themselves.
 */
public final class BridgeClient implements AutoCloseable {

	public interface Listener {

		void onWelcome(Frame.Welcome welcome);

		void onDenied(Frame.Denied denied);

		void onFrame(Frame frame);

		/** The connection ended, or a connect attempt failed. The client is already scheduling the next try. */
		void onDisconnect(String reason);
	}

	private static final long MIN_BACKOFF_MILLIS = 500;
	private static final long MAX_BACKOFF_MILLIS = 30_000;

	private final URI uri;
	private final Supplier<Frame.Hello> hello;
	private final Listener listener;
	private final long maxBackoffMillis;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "skord-bridge-client");
		thread.setDaemon(true);
		return thread;
	});

	// Everything below is guarded by this.
	private @Nullable WebSocket socket;
	private int generation;
	private int attempts;
	private boolean closed;
	private boolean denied;
	private long heartbeatMillis;
	private volatile long lastInbound;

	public BridgeClient(URI uri, Supplier<Frame.Hello> hello, Listener listener) {
		this(uri, hello, listener, MAX_BACKOFF_MILLIS);
	}

	public BridgeClient(URI uri, Supplier<Frame.Hello> hello, Listener listener, long maxBackoffMillis) {
		this.uri = uri;
		this.hello = hello;
		this.listener = listener;
		this.maxBackoffMillis = maxBackoffMillis;
	}

	public void start() {
		scheduler.execute(this::connect);
	}

	/** Sends a frame. Returns false if there's no live connection; nothing is queued. */
	public boolean send(Frame frame) {
		WebSocket current;
		synchronized (this) {
			current = socket;
		}
		if (current == null)
			return false;
		current.sendText(FrameCodec.encode(frame), true);
		return true;
	}

	public synchronized boolean connected() {
		return socket != null && heartbeatMillis > 0;
	}

	@Override
	public void close() {
		WebSocket current;
		synchronized (this) {
			closed = true;
			current = socket;
			socket = null;
		}
		if (current != null)
			current.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		scheduler.shutdownNow();
	}

	private void connect() {
		int mine;
		synchronized (this) {
			if (closed || denied)
				return;
			mine = ++generation;
		}
		http.newWebSocketBuilder().buildAsync(uri, new Handler(mine)).whenComplete((webSocket, error) -> {
			if (error != null)
				lost(mine, "could not connect: " + (error.getCause() != null ? error.getCause() : error).getMessage());
		});
	}

	// Called once per connection generation, from whichever callback notices first.
	private void lost(int mine, String reason) {
		long delay;
		synchronized (this) {
			if (mine != generation || closed)
				return;
			generation++; // the rest of this connection's callbacks are stale
			WebSocket current = socket;
			socket = null;
			heartbeatMillis = 0;
			if (current != null)
				current.abort();
			if (denied)
				return;
			long ceiling = Math.min(maxBackoffMillis, MIN_BACKOFF_MILLIS << Math.min(attempts++, 16));
			delay = ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1);
		}
		listener.onDisconnect(reason);
		try {
			scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
		} catch (RuntimeException closedDuringShutdown) {
			// close() won the race
		}
	}

	private void watchHeartbeat(int mine) {
		long interval;
		synchronized (this) {
			interval = heartbeatMillis;
		}
		if (interval <= 0)
			return;
		scheduler.schedule(() -> {
			WebSocket current;
			synchronized (this) {
				if (mine != generation || closed)
					return;
				current = socket;
			}
			if (System.currentTimeMillis() - lastInbound > interval * 3) {
				lost(mine, "the bot stopped answering");
				return;
			}
			if (current != null)
				current.sendPing(java.nio.ByteBuffer.allocate(0));
			watchHeartbeat(mine);
		}, interval, TimeUnit.MILLISECONDS);
	}

	private final class Handler implements WebSocket.Listener {

		private final int mine;
		private final StringBuilder text = new StringBuilder();

		Handler(int mine) {
			this.mine = mine;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			synchronized (BridgeClient.this) {
				if (mine != generation || closed) {
					webSocket.abort();
					return;
				}
				socket = webSocket;
			}
			lastInbound = System.currentTimeMillis();
			webSocket.sendText(FrameCodec.encode(hello.get()), true);
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			lastInbound = System.currentTimeMillis();
			text.append(data);
			if (text.length() > FrameCodec.MAX_FRAME_CHARS) {
				text.setLength(0);
				lost(mine, "the bot sent an oversized frame");
				return null;
			}
			if (last) {
				String whole = text.toString();
				text.setLength(0);
				handle(whole);
			}
			webSocket.request(1);
			return null;
		}

		private void handle(String whole) {
			Frame frame;
			try {
				frame = FrameCodec.decode(whole);
			} catch (FrameCodec.FrameException e) {
				return; // a frame we can't read is dropped; the connection stays
			}
			switch (frame) {
				case Frame.Welcome welcome -> {
					synchronized (BridgeClient.this) {
						attempts = 0;
						heartbeatMillis = Math.max(1000, welcome.heartbeat());
					}
					listener.onWelcome(welcome);
					watchHeartbeat(mine);
				}
				case Frame.Denied refused -> {
					synchronized (BridgeClient.this) {
						denied = true;
					}
					listener.onDenied(refused);
					lost(mine, "denied: " + refused.reason());
				}
				default -> listener.onFrame(frame);
			}
		}

		@Override
		public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
			lastInbound = System.currentTimeMillis();
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
			lastInbound = System.currentTimeMillis();
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			lost(mine, "connection closed" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			lost(mine, "connection error: " + error.getMessage());
		}
	}
}
