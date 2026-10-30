package io.github.itsnotethann.skord.bridge.paper;

import java.nio.file.Path;
import java.util.logging.Level;

import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class PaperPlatform implements BridgePlatform {

	private final Plugin plugin;

	public PaperPlatform(Plugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public String name() {
		return "paper";
	}

	@Override
	public Path dataDirectory() {
		return plugin.getDataFolder().toPath();
	}

	@Override
	public void runOnMain(Runnable task) {
		if (Bukkit.isPrimaryThread())
			task.run();
		else if (plugin.isEnabled())
			Bukkit.getScheduler().runTask(plugin, task);
	}

	@Override
	public void log(Level level, String message) {
		plugin.getLogger().log(level, message);
	}
}
