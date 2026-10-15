package io.github.itsnotethann.skord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Pattern;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.bridge.BridgeHost;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.jda.JdaDiscord;
import io.github.itsnotethann.skord.syntax.BridgeSyntax;
import io.github.itsnotethann.skord.syntax.SkordSyntax;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.platform.AddonHandle;
import org.skriptlang.skript.platform.AddonRegistry;
import org.skriptlang.skript.platform.EventBus;
import org.skriptlang.skript.platform.LogSink;
import org.skriptlang.skript.platform.Platform;
import org.skriptlang.skript.platform.PlatformEnvironment;
import org.skriptlang.skript.platform.PlatformProvider;
import org.skriptlang.skript.platform.PlatformScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots a bot: log in, wait for the cache, load scripts, then start delivering events.
 * <p>
 * Logging in <i>before</i> Skript loads is deliberate. Variables hold Discord objects by ID, and resolving
 * those IDs while variables load needs a ready JDA.
 */
public final class SkordHost implements PlatformProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");
	private static final Logger SKRIPT_LOGGER = LoggerFactory.getLogger("Skript");
	private static final Set<GatewayIntent> PRIVILEGED =
		EnumSet.of(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES, GatewayIntent.MESSAGE_CONTENT);

	private static @Nullable SkordHost instance;

	private final File directory;
	private final ScriptThread scriptThread = new ScriptThread();
	private final JdaEventBus eventBus = new JdaEventBus(scriptThread);
	private final AtomicBoolean stopping = new AtomicBoolean();
	private final AtomicInteger errors = new AtomicInteger();
	private final PlatformEnvironment environment;
	private @Nullable AddonRegistry addonRegistry;
	private @Nullable JDA jda;
	private @Nullable BridgeHost bridge;

	private SkordHost(File directory) {
		this.directory = directory;
		this.environment = new PlatformEnvironment() {
			// Skript parses this as a Minecraft version for version-gated syntax. A bot has no Minecraft;
			// report the same version uncommon's own headless host does.
			@Override
			public String platformVersion() {
				return "1.21.0";
			}

			@Override
			public boolean isPrimaryThread() {
				return scriptThread.isPrimaryThread();
			}

			@Override
			public File serverDirectory() {
				return SkordHost.this.directory;
			}

			@Override
			public Logger logger() {
				return SKRIPT_LOGGER;
			}
		};
	}

	/** The logged-in JDA. Valid from the moment scripts start loading. */
	public static JDA jda() {
		SkordHost host = instance;
		if (host == null || host.jda == null)
			throw new IllegalStateException("skord has not logged in yet");
		return host.jda;
	}

	/**
	 * {@code --check} loads every script without logging in, reports parse errors and exits: non-zero if any
	 * script failed. Nothing touches Discord, so it needs no token, and suits CI.
	 */
	public static void main(String[] args) throws Exception {
		boolean check = List.of(args).contains("--check");
		Path directory = Path.of("").toAbsolutePath();
		SkordHost host = new SkordHost(directory.toFile());
		instance = host;
		Discord.install(new JdaDiscord(host.eventBus));

		if (!check) {
			Path configFile = directory.resolve("skord.yml");
			if (SkordConfig.writeDefault(configFile)) {
				seedExampleScript(directory);
				LOGGER.info("First start: created {} and an example script in Skript/scripts/.", configFile);
				LOGGER.info("Paste your bot token into skord.yml, then start skord again.");
				System.exit(0);
				return;
			}
			SkordConfig config;
			try {
				config = SkordConfig.load(configFile);
			} catch (IllegalStateException e) {
				LOGGER.error(e.getMessage());
				System.exit(1);
				return;
			}
			LOGGER.info(System.getenv(SkordConfig.TOKEN_ENV) != null
				? "Logging in (token from " + SkordConfig.TOKEN_ENV + ")..."
				: "Logging in...");
			try {
				host.jda = JDABuilder.createLight(config.token(), config.intents())
					.addEventListeners(host.eventBus)
					.build()
					.awaitReady();
			} catch (InvalidTokenException e) {
				LOGGER.error("Discord rejected the bot token. Check it, or reset it in the developer portal.");
				System.exit(1);
				return;
			} catch (IllegalStateException e) {
				// JDA reports a refused privileged intent (close code 4014) only through this message.
				if (String.valueOf(e.getMessage()).contains("intent")) {
					List<String> privileged = config.intents().stream()
						.filter(intent -> PRIVILEGED.contains(intent))
						.map(intent -> intent.name().toLowerCase(Locale.ROOT).replace('_', '-'))
						.toList();
					LOGGER.error("Discord refused a privileged intent. skord.yml asks for {}: turn each on under "
						+ "Bot > Privileged Gateway Intents in the developer portal, or remove it from skord.yml.",
						privileged);
					System.exit(1);
					return;
				}
				throw e;
			}
			LOGGER.info("Logged in as {}", host.jda.getSelfUser().getName());
			CommandSync.install(directory.resolve(".skord").resolve("commands.hash"), config.devGuild(),
				CommandSync.jda(host.jda));
				host.bridge = BridgeHost.start(directory, config.bridge(),
					() -> new Frame.BotInfo(host.jda.getSelfUser().getId(), host.jda.getSelfUser().getName()),
					host.scriptThread::execute);
		}

		Platform.install(host);
		CountDownLatch loaded = new CountDownLatch(1);
		host.boot(check ? loaded::countDown : host::goLive);
		if (check) {
			loaded.await();
			host.stop();
			int errors = host.errors.get();
			LOGGER.info(errors == 0 ? "Check passed." : "Check failed: {} error(s).", errors);
			System.exit(errors == 0 ? 0 : 1);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(host::stop, "skord-shutdown"));
		LOGGER.info("Ready. Type \"help\" for console commands.");
		// Returns at once without stdin (a service, a container); the script thread and JDA's threads keep
		// the JVM up, and a signal still stops it through the shutdown hook.
		new Console(host.scriptThread).run();
	}

	// Runs on the script thread. The command sync is a request like any other: it doesn't hold up going live.
	private void goLive() {
		CommandSync.sync();
		eventBus.goLive();
		eventBus.fire(new ReadyEvent());
	}

	private void boot(Runnable whenLoaded) throws InterruptedException {
		CountDownLatch enabled = new CountDownLatch(1);
		Throwable[] failure = new Throwable[1];
		scriptThread.execute(() -> {
			try {
				Skript skript = new Skript();
				Skript.onRegistration(() -> {
						SkordSyntax.register();
						BridgeSyntax.register();
					});
				Skript.onFinishedLoading(() -> scriptThread.execute(whenLoaded));
				skript.setEnabled(true);
				skript.onEnable();
				Skript.closeUnsafeSkript();
			} catch (Throwable throwable) {
				failure[0] = throwable;
			} finally {
				enabled.countDown();
			}
		});
		enabled.await();
		if (failure[0] != null) {
			LOGGER.error("Skript failed to start", failure[0]);
			stop();
			System.exit(2);
		}
	}

	// Only when Skript's scripts folder doesn't exist yet. Skript then sees the folder and skips its own
	// examples, which are written for Minecraft and would not load here.
	private static void seedExampleScript(Path directory) throws IOException {
		Path scripts = directory.resolve("Skript").resolve("scripts");
		if (Files.exists(scripts))
			return;
		Files.createDirectories(scripts);
		try (InputStream example = SkordHost.class.getResourceAsStream("/skord/examples/ping.sk")) {
			if (example != null)
				Files.copy(example, scripts.resolve("ping.sk"));
		}
	}

	private void stop() {
		if (!stopping.compareAndSet(false, true))
			return;
		LOGGER.info("Shutting down...");
		CountDownLatch disabled = new CountDownLatch(1);
		scriptThread.execute(() -> {
			try {
				Skript skript = Skript.getInstance();
				if (skript.isEnabled()) {
					skript.onDisable(); // flushes variables
					skript.setEnabled(false);
				}
			} catch (Throwable throwable) {
				LOGGER.error("Error while disabling Skript", throwable);
			} finally {
				disabled.countDown();
			}
		});
		try {
			disabled.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		scriptThread.shutdown();
		if (bridge != null)
			bridge.stop();
		if (jda != null)
			jda.shutdown();
	}

	@Override
	public PlatformEnvironment environment() {
		return environment;
	}

	// Skript's lang files mark up messages with colour tags (<error_color>, <gold>, <reset>...) meant for a
	// chat renderer. A console has none, so they come out as noise.
	private static final Pattern COLOUR_TAG = Pattern.compile("</?(?:[a-z_]+|#[0-9a-fA-F]{6})>");

	@Override
	public LogSink logSink() {
		return (level, raw, error) -> {
			String message = COLOUR_TAG.matcher(raw).replaceAll("");
			if (level.intValue() >= Level.SEVERE.intValue()) {
				errors.incrementAndGet();
				SKRIPT_LOGGER.error(message, error);
			}
			else if (level.intValue() >= Level.WARNING.intValue())
				SKRIPT_LOGGER.warn(message, error);
			else
				SKRIPT_LOGGER.info(message, error);
		};
	}

	@Override
	public PlatformScheduler scheduler() {
		return scriptThread;
	}

	@Override
	public EventBus eventBus() {
		return eventBus;
	}

	@Override
	public synchronized AddonRegistry addonRegistry() {
		if (addonRegistry == null) {
			addonRegistry = ServiceLoader.load(AddonRegistry.class, SkordHost.class.getClassLoader())
				.findFirst()
				.orElseGet(NoAddons::new);
		}
		return addonRegistry;
	}

	private static final class NoAddons implements AddonRegistry {

		@Override
		public @Nullable AddonHandle load(File jar) {
			return null;
		}

		@Override
		public List<AddonHandle> addons() {
			return List.of();
		}

		@Override
		public @Nullable AddonHandle providingAddon(Class<?> from) {
			return null;
		}
	}
}
