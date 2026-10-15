package io.github.itsnotethann.skord;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.log.TimingLogHandler;
import io.github.itsnotethann.skord.command.CommandSync;
import org.skriptlang.skript.lang.script.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reloading, enabling and disabling scripts, shared by the console and testbot. Each finishes by syncing slash
 * commands; the diff in {@link CommandSync} keeps that free when no command changed.
 * <p>
 * Call on the script thread. The returned future completes once loading and the sync are done, and fails if
 * the target doesn't exist.
 */
public final class Scripts {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private Scripts() {}

	/** {@code all}, {@code config}, a script or a folder. */
	public static CompletableFuture<Void> reload(String what) {
		return held(() -> doReload(what));
	}

	public static CompletableFuture<Void> enable(String name) {
		return held(() -> doEnable(name));
	}

	public static CompletableFuture<Void> disable(String name) {
		return held(() -> doDisable(name));
	}

	// The automatic sync on script changes waits: this operation syncs once, when it's completely done.
	private static CompletableFuture<Void> held(Supplier<CompletableFuture<Void>> operation) {
		CommandSync.hold();
		CompletableFuture<Void> result;
		try {
			result = operation.get();
		} catch (RuntimeException e) {
			CommandSync.release();
			throw e;
		}
		return result.whenComplete((done, error) -> CommandSync.release());
	}

	private static CompletableFuture<Void> doReload(String what) {
		if (what.equalsIgnoreCase("config")) {
			try (TimingLogHandler timing = new TimingLogHandler().start()) {
				SkriptConfig.load();
				LOGGER.info("Reloaded config.sk in {} ms.", timing.getTimeTaken());
			}
			return CompletableFuture.completedFuture(null);
		}
		if (what.equalsIgnoreCase("all")) {
			LOGGER.info("Reloading config.sk and all scripts...");
			TimingLogHandler timing = new TimingLogHandler().start();
			SkriptConfig.load();
			ScriptLoader.unloadScripts(ScriptLoader.getLoadedScripts());
			return finish("all scripts", timing, ScriptLoader.loadScripts(Skript.getInstance().getScriptsFolder(), timing));
		}

		File file = ScriptLoader.getScriptFromName(normalise(what));
		if (file == null)
			return missing("No script or folder called '" + what + "' in " + Skript.getInstance().getScriptsFolder() + ".");
		TimingLogHandler timing = new TimingLogHandler().start();
		if (file.isDirectory()) {
			ScriptLoader.unloadScripts(ScriptLoader.getScripts(file));
		} else {
			Script script = ScriptLoader.getScript(file);
			if (script != null)
				ScriptLoader.unloadScript(script);
		}
		LOGGER.info("Reloading {}...", what);
		return finish(what, timing, ScriptLoader.loadScripts(file, timing));
	}

	private static CompletableFuture<Void> doEnable(String name) {
		Path enabled = Skript.getInstance().getScriptsFolder().toPath().resolve(withExtension(normalise(name)));
		Path disabled = enabled.resolveSibling(ScriptLoader.DISABLED_SCRIPT_PREFIX + enabled.getFileName());
		if (!Files.exists(disabled))
			return missing(Files.exists(enabled) ? "'" + name + "' is already enabled." : "No disabled script called '" + name + "'.");
		try {
			Files.move(disabled, enabled);
		} catch (IOException e) {
			LOGGER.error("Could not enable '{}'", name, e);
			return CompletableFuture.failedFuture(e);
		}
		TimingLogHandler timing = new TimingLogHandler().start();
		return finish(name, timing, ScriptLoader.loadScripts(enabled.toFile(), timing));
	}

	private static CompletableFuture<Void> doDisable(String name) {
		File file = ScriptLoader.getScriptFromName(normalise(name));
		Script script = file == null ? null : ScriptLoader.getScript(file);
		if (file == null || script == null || !ScriptLoader.getLoadedScripts().contains(script))
			return missing("'" + name + "' isn't an enabled script.");
		ScriptLoader.unloadScript(script);
		try {
			Files.move(file.toPath(), file.toPath().resolveSibling(ScriptLoader.DISABLED_SCRIPT_PREFIX + file.getName()));
			LOGGER.info("Unloaded and disabled '{}'.", name);
		} catch (IOException e) {
			LOGGER.error("Unloaded '{}', but could not rename it to disable it", name, e);
		}
		return sync();
	}

	private static CompletableFuture<Void> finish(String what, TimingLogHandler timing, CompletableFuture<?> loading) {
		return loading.handle((info, error) -> {
			timing.close();
			if (error != null)
				LOGGER.error("Reloading {} failed", what, error);
			else
				LOGGER.info("Reloaded {} in {} ms.", what, timing.getTimeTaken());
			return null;
		}).thenCompose(done -> sync());
	}

	// Loading may finish on a loader thread; the registry is read on the script thread.
	private static CompletableFuture<Void> sync() {
		CompletableFuture<Void> synced = new CompletableFuture<>();
		Skript.scheduler().sync(() -> CommandSync.sync().whenComplete((sent, error) -> synced.complete(null)), 0, -1);
		return synced;
	}

	private static CompletableFuture<Void> missing(String message) {
		LOGGER.info(message);
		return CompletableFuture.failedFuture(new IllegalArgumentException(message));
	}

	private static String normalise(String name) {
		return name.replace('/', File.separatorChar).replace('\\', File.separatorChar);
	}

	private static String withExtension(String name) {
		return name.endsWith(".sk") ? name : name + ".sk";
	}

}
