package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.github.itsnotethann.skord.protocol.Frame;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot's bridge as one object: the link store, the listening server when {@code bridge.port} is set, and the
 * router that ties servers' commands and interactions to the bot. Installed once at boot; the console reaches it
 * through here.
 */
public final class BridgeHost {

	/** {@code bridge:} in skord.yml. A port of 0 leaves the bridge off. */
	public record Settings(int port, String bind, long timeoutMillis) {

		public static final long DEFAULT_TIMEOUT_MILLIS = 5_000;
		public static final Settings OFF = new Settings(0, "0.0.0.0", DEFAULT_TIMEOUT_MILLIS);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private static @Nullable BridgeHost installed;

	private final LinkStore links;
	private final @Nullable BridgeServer server;
	private final @Nullable BridgeRouter router;

	private BridgeHost(LinkStore links, @Nullable BridgeServer server, @Nullable BridgeRouter router) {
		this.links = links;
		this.server = server;
		this.router = router;
	}

	/**
	 * Loads the links and, if a port is set, registers every server's saved commands and starts listening. A port
	 * that can't be bound is logged and leaves the bridge off rather than stopping the bot.
	 * <p>
	 * Call this before scripts load: saved commands must already be in the registry when the first sync runs, so
	 * a restart sends Discord nothing.
	 *
	 * @param script runs a task on the script thread
	 */
	public static synchronized BridgeHost start(Path directory, Settings settings, Supplier<Frame.BotInfo> bot,
												Executor script) throws IOException {
		Path state = directory.resolve(".skord");
		LinkStore links = new LinkStore(state.resolve("links.yml"));
		BridgeServer server = null;
		BridgeRouter router = null;
		if (settings.port() > 0) {
			try {
				router = new BridgeRouter(state.resolve("servers"), script, settings.timeoutMillis());
				server = new BridgeServer(new InetSocketAddress(settings.bind(), settings.port()), links, bot, router);
				router.attach(server);
				router.restore(links.list().stream().map(LinkStore.Link::name).toList());
				server.startAndWait();
				LOGGER.info("Bridge listening on {}:{}", settings.bind(), settings.port());
			} catch (Exception e) {
				LOGGER.error("Bridge could not listen on {}:{}: {}", settings.bind(), settings.port(), e.getMessage());
				server = null;
				router = null;
			}
		}
		installed = new BridgeHost(links, server, router);
		return installed;
	}

	public static synchronized @Nullable BridgeHost installed() {
		return installed;
	}

	public LinkStore links() {
		return links;
	}

	public @Nullable BridgeServer server() {
		return server;
	}

	public @Nullable BridgeRouter router() {
		return router;
	}

	/** Runs one {@code sk link} command and returns what to print. */
	public List<String> link(String[] args) {
		return LinkCommands.run(links, server, name -> {
			if (router != null)
				router.forget(name);
		}, args);
	}

	public void stop() {
		if (server != null)
			server.shutdown();
	}
}
