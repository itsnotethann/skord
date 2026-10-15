package io.github.itsnotethann.skord.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.platform.PlatformTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the scripts' slash commands to Discord, but only when they changed. The hash of the last set sent lives
 * in {@code .skord/commands.hash}; a load or reload whose commands hash the same sends nothing. When they
 * differ, one bulk update replaces the whole set, which also removes commands no script declares any more.
 * <p>
 * {@code commands.dev-guild} in {@code skord.yml} registers in that guild instead of globally. The scope is part
 * of the hash, so switching it re-sends.
 */
public final class CommandSync {

	/** Where the update goes. {@link #jda} is the real one; testbot counts the requests instead. */
	public interface Sink {
		CompletionStage<?> update(long guildId, List<CommandData> commands);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private static final AtomicInteger HOLDS = new AtomicInteger();

	private static @Nullable CommandSync installed;
	private static @Nullable PlatformTask pending;

	private final Path hashFile;
	private final long guildId;
	private final Sink sink;
	// The hash Discord has, or is being sent. Null when unknown (a failed update, or none read yet).
	private @Nullable String current;

	private CommandSync(Path hashFile, long guildId, Sink sink) {
		this.hashFile = hashFile;
		this.guildId = guildId;
		this.sink = sink;
		try {
			current = Files.exists(hashFile) ? Files.readString(hashFile, StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			current = null;
		}
	}

	/**
	 * Turns syncing on. Without this ({@code --check}) {@link #sync} does nothing.
	 * <p>
	 * From then on, any script loading or unloading schedules a sync a tick later, however it happened: the
	 * console, the engine's own {@code reload script} effect, an addon. Several changes in a row coalesce into
	 * one sync, and none runs while a {@link #hold} is open; the holder syncs when it's done.
	 */
	public static synchronized void install(Path hashFile, long guildId, Sink sink) {
		installed = new CommandSync(hashFile, guildId, sink);
		ScriptLoader.eventRegistry().register(ScriptLoader.ScriptLoadEvent.class, (parser, script) -> later());
		ScriptLoader.eventRegistry().register(ScriptLoader.ScriptUnloadEvent.class, (parser, script) -> later());
	}

	/** Holds off the automatic sync while an operation that will sync at its end (a console reload) runs. */
	public static void hold() {
		HOLDS.incrementAndGet();
	}

	public static void release() {
		HOLDS.decrementAndGet();
	}

	/** Schedules a sync a tick from now, coalescing with any already waiting. For changes that aren't a script load. */
	public static void request() {
		later();
	}

	private static synchronized void later() {
		if (pending != null)
			pending.cancel();
		pending = Skript.scheduler().sync(() -> {
			synchronized (CommandSync.class) {
				pending = null;
			}
			if (HOLDS.get() == 0)
				sync();
		}, 1, -1);
	}

	public static Sink jda(JDA jda) {
		return (guildId, commands) -> {
			if (guildId == 0)
				return jda.updateCommands().addCommands(commands).submit();
			Guild guild = jda.getGuildById(guildId);
			if (guild == null)
				return CompletableFuture.failedFuture(new IllegalStateException("commands.dev-guild is " + guildId
					+ ", but the bot isn't in a guild with that ID"));
			return guild.updateCommands().addCommands(commands).submit();
		};
	}

	/**
	 * Sends the current commands if they differ from what Discord has. Call on the script thread after scripts
	 * load or reload. Completes with the number of requests sent: 0 or 1.
	 */
	public static synchronized CompletionStage<Integer> sync() {
		CommandSync sync = installed;
		if (sync == null)
			return CompletableFuture.completedFuture(0);
		return sync.run();
	}

	private CompletionStage<Integer> run() {
		List<CommandData> commands = CommandRegistry.build();
		String hash = hash(commands);
		if (hash.equals(current))
			return CompletableFuture.completedFuture(0);
		current = hash;
		String scope = guildId == 0 ? "globally" : "in guild " + guildId;
		LOGGER.info("Registering {} slash command(s) {}...", commands.size(), scope);
		return sink.update(guildId, commands).handle((result, error) -> {
			if (error != null) {
				synchronized (CommandSync.class) {
					if (hash.equals(current))
						current = null; // unknown now: the next load tries again
				}
				LOGGER.error("Registering slash commands failed: {}", error.getMessage());
				return 1;
			}
			try {
				Files.createDirectories(hashFile.getParent());
				Files.writeString(hashFile, hash, StandardCharsets.UTF_8);
			} catch (IOException e) {
				LOGGER.warn("Could not save {}; the next start re-sends the commands", hashFile, e);
			}
			LOGGER.info("Registered slash commands {}.", scope);
			return 1;
		});
	}

	private String hash(List<CommandData> commands) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(("scope:" + guildId + "\n").getBytes(StandardCharsets.UTF_8));
			for (CommandData command : commands) {
				digest.update(command.toData().toJson());
				digest.update((byte) '\n');
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
