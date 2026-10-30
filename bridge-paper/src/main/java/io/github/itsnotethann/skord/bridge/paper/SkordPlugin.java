package io.github.itsnotethann.skord.bridge.paper;

import java.util.Map;

import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkordPlugin extends JavaPlugin {

	private Bridge bridge;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		ConfigurationSection section = getConfig();
		String mode = section.getString("mode", "remote");
		if (!"remote".equalsIgnoreCase(mode)) {
			getLogger().severe("mode '" + mode + "' is not supported by this build; use 'remote'");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		Map<String, Object> values = section.getValues(false);
		bridge = Bridge.start(BridgeConfig.of(values), new PaperPlatform(this));
		getServer().getServicesManager().register(Bridge.class, bridge, this, ServicePriority.Normal);
	}

	@Override
	public void onDisable() {
		getServer().getServicesManager().unregisterAll(this);
		if (bridge != null) {
			bridge.close();
			bridge = null;
		}
	}
}
