package io.github.itsnotethann.skord.embedded;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.JdaEventBus;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.jda.JdaDiscord;
import io.github.itsnotethann.skord.jda.JdaHost;
import io.github.itsnotethann.skord.shared.SharedCalls;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;

/**
 * skord as the bot of one server: the platform jar logs in, installs the Discord port, and hands the server's own
 * Skript the events. There is no bridge, so no link secret and no scopes.
 * <p>
 * Order: {@link #start} (or {@link #attach}) before scripts load; {@link #ready} on the script thread once they have.
 */
public final class Embedded {

	private final JdaEventBus bus;
	private final BridgePlatform platform;
	private final long devGuild;
	private final CommandSync.Sink sink;
	private volatile JDA jda;

	private Embedded(JdaEventBus bus, BridgePlatform platform, long devGuild, CommandSync.Sink sink) {
		this.bus = bus;
		this.platform = platform;
		this.devGuild = devGuild;
		this.sink = sink;
	}

	/** Logs in, then wires everything. Blocks until the gateway is ready, so call it off the server's main thread. */
	public static Embedded start(BridgePlatform platform, BooleanSupplier onMain, EmbeddedConfig config)
			throws InterruptedException {
		JDA[] holder = new JDA[1];
		JdaEventBus bus = new JdaEventBus(host(platform, onMain, () -> holder[0]));
		JDA logged = JDABuilder.createLight(config.token(), config.intents())
			.setRawEventsEnabled(true)
			.addEventListeners(bus)
			.build()
			.awaitReady();
		holder[0] = logged;
		Embedded embedded = attach(platform, onMain, bus, new JdaDiscord(bus, host(platform, onMain, () -> logged)),
			CommandSync.jda(logged), config.devGuild());
		embedded.jda = logged;
		platform.log(Level.INFO, "Logged in as " + logged.getSelfUser().getName());
		return embedded;
	}

	/** The wiring alone, over any {@link Discord} and command sink. {@link #start} is this plus a real login. */
	public static Embedded attach(BridgePlatform platform, BooleanSupplier onMain, JdaEventBus bus, Discord discord,
			CommandSync.Sink sink, long devGuild) {
		bus.delegate(Skript::eventBus);
		Discord.install(discord);
		SharedCalls.install(new NoCalls());
		return new Embedded(bus, platform, devGuild, sink);
	}

	public static JdaHost host(BridgePlatform platform, BooleanSupplier onMain, Supplier<JDA> jda) {
		return new JdaHost() {
			@Override
			public JDA jda() {
				JDA current = jda.get();
				if (current == null)
					throw new IllegalStateException("skord has not logged in yet");
				return current;
			}

			@Override
			public boolean onScriptThread() {
				return onMain.getAsBoolean();
			}

			@Override
			public void runOnScriptThread(Runnable task) {
				platform.runOnMain(task);
			}
		};
	}

	/** Registers the bridge-only syntax as load errors. Call where the platform registers {@code SkordSyntax}. */
	public static void registerSyntax() {
		EmbeddedSyntax.register();
	}

	/** Turns command syncing on. Call once, before the first script loads. */
	public void installCommandSync() {
		CommandSync.install(platform.dataDirectory().resolve("commands.hash"), devGuild, sink);
	}

	/** On the script thread, after the scripts loaded: sync commands, start delivering events, fire {@code on ready}. */
	public void ready() {
		CommandSync.sync();
		bus.goLive();
		bus.fire(new ReadyEvent());
		JDA logged = jda;
		if (logged != null && logged.getGatewayIntents().contains(net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MEMBERS))
			CompletableFuture.runAsync(() -> bus.sweepBoosters(logged),
				CompletableFuture.delayedExecutor(120, TimeUnit.SECONDS));
	}

	public void stop() {
		JDA logged = jda;
		if (logged != null)
			logged.shutdown();
	}
}
