package io.github.itsnotethann.skord.bridge.minestom;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Level;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.embedded.Embedded;
import io.github.itsnotethann.skord.embedded.EmbeddedConfig;
import io.github.itsnotethann.skord.remote.RemoteServer;
import io.github.itsnotethann.skord.syntax.SkordSyntax;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

public final class SkordAddon extends JavaPlugin {

	private Embedded embedded;
	private RemoteServer remote;

	@Override
	public void onEnable() {
		try {
			Map<?, ?> values = config();
			String mode = String.valueOf(values.get("mode") == null ? "embedded" : values.get("mode"));
			if ("remote".equalsIgnoreCase(mode)) {
				startRemote(values);
				return;
			}
			if (!"embedded".equalsIgnoreCase(mode)) {
				getLogger().severe("mode '" + mode + "' is unknown; use 'embedded' or 'remote'");
				return;
			}
			Skript.registerAddon(this);
			SkordSyntax.register();
			Embedded.registerSyntax();
			EmbeddedConfig config = EmbeddedConfig.of(values, System.getenv());
			embedded = Embedded.start(getDataFolder().toPath(), getLogger(), config);
			embedded.installCommandSync();
			Skript.onFinishedLoading(embedded::ready);
		} catch (IllegalArgumentException e) {
			getLogger().severe(e.getMessage());
		} catch (IOException | InterruptedException e) {
			getLogger().log(Level.SEVERE, "skord could not start", e);
		}
	}

	private void startRemote(Map<?, ?> values) {
		Skript.registerAddon(this);
		SkordSyntax.register();
		BridgePlatform platform = new MinestomPlatform(getDataFolder().toPath(), getLogger());
		BridgeConfig config = BridgeConfig.of(values);
		RemoteServer server = new RemoteServer(platform);
		remote = server;
		Skript.onFinishedLoading(() -> {
			if (server.bridge() == null)
				server.start(config);
			server.publish();
		});
	}

	@Override
	public void onDisable() {
		if (remote != null) {
			Bridge bridge = remote.bridge();
			if (bridge != null)
				bridge.close();
			remote = null;
		}
		if (embedded != null) {
			embedded.stop();
			embedded = null;
		}
	}

	private Map<?, ?> config() throws IOException {
		Path folder = getDataFolder().toPath();
		Files.createDirectories(folder);
		Path file = folder.resolve("config.yml");
		if (!Files.exists(file)) {
			try (InputStream in = getResource("config.yml")) {
				if (in != null)
					Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Object loaded = new Yaml().load(reader);
			return loaded instanceof Map<?, ?> map ? map : Map.of();
		}
	}
}
