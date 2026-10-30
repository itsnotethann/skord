package io.github.itsnotethann.skord.bridge.minestom;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgeConfig;

public final class SkordMinestom {

	private SkordMinestom() {}

	public static Bridge connect(Path dataDirectory, Map<?, ?> config) throws IOException {
		Files.createDirectories(dataDirectory);
		Object mode = config.get("mode");
		if (mode != null && !"remote".equalsIgnoreCase(String.valueOf(mode)))
			throw new IllegalArgumentException("bridge-minestom runs mode 'remote' only, got '" + mode + "'");
		return Bridge.start(BridgeConfig.of(config), new MinestomPlatform(dataDirectory, Logger.getLogger("skord")));
	}
}
