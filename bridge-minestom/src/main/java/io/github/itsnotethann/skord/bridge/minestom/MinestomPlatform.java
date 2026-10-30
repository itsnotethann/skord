package io.github.itsnotethann.skord.bridge.minestom;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import net.minestom.server.MinecraftServer;

public final class MinestomPlatform implements BridgePlatform {

	private final Path dataDirectory;
	private final Logger logger;

	public MinestomPlatform(Path dataDirectory, Logger logger) {
		this.dataDirectory = dataDirectory;
		this.logger = logger;
	}

	@Override
	public String name() {
		return "minestom";
	}

	@Override
	public Path dataDirectory() {
		return dataDirectory;
	}

	@Override
	public void runOnMain(Runnable task) {
		MinecraftServer.getSchedulerManager().scheduleNextTick(task);
	}

	@Override
	public void log(Level level, String message) {
		logger.log(level, message);
	}
}
