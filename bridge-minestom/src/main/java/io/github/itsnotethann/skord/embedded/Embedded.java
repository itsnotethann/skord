package io.github.itsnotethann.skord.embedded;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.itsnotethann.skord.JdaEventBus;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.jda.JdaDiscord;
import io.github.itsnotethann.skord.jda.JdaHost;
import io.github.itsnotethann.skord.shared.SharedCalls;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.minestom.server.MinecraftServer;

public final class Embedded {

	private final JdaEventBus bus;
	private final java.nio.file.Path dataDirectory;
	private final long devGuild;
	private final CommandSync.Sink sink;
	private final Logger logger;
	private volatile JDA jda;

	private Embedded(JdaEventBus bus, java.nio.file.Path dataDirectory, long devGuild, CommandSync.Sink sink,
			Logger logger) {
		this.bus = bus;
		this.dataDirectory = dataDirectory;
		this.devGuild = devGuild;
		this.sink = sink;
		this.logger = logger;
	}

	public static Embedded start(java.nio.file.Path dataDirectory, Logger logger, EmbeddedConfig config)
			throws InterruptedException {
		JDA[] holder = new JDA[1];
		JdaEventBus bus = new JdaEventBus(host(() -> holder[0]));
		JDA logged = JDABuilder.createLight(config.token(), config.intents())
			.setRawEventsEnabled(true)
			.addEventListeners(bus)
			.build()
			.awaitReady();
		holder[0] = logged;
		Discord.install(new JdaDiscord(bus, host(() -> logged)));
		SharedCalls.install(new NoCalls());
		Embedded embedded = new Embedded(bus, dataDirectory, config.devGuild(), CommandSync.jda(logged), logger);
		embedded.jda = logged;
		logger.log(Level.INFO, "Logged in as " + logged.getSelfUser().getName());
		return embedded;
	}

	public static JdaHost host(Supplier<JDA> jda) {
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
				return false;
			}

			@Override
			public void runOnScriptThread(Runnable task) {
				MinecraftServer.getSchedulerManager().scheduleNextTick(task);
			}
		};
	}

	public static void registerSyntax() {
		EmbeddedSyntax.register();
	}

	public void installCommandSync() {
		CommandSync.install(dataDirectory.resolve("commands.hash"), devGuild, sink);
	}

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
