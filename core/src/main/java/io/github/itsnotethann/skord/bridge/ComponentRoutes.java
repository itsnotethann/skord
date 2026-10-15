package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which server sent which message, for the messages that carry components, so a click goes back to the process
 * that sent the button. Persisted and capped: past the cap the oldest message is forgotten, and its buttons fall
 * back to the first subscriber whose filter matches.
 */
final class ComponentRoutes {

	static final int CAP = 5_000;

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");
	private static final Gson GSON = new Gson();

	private final Path file;
	private final int cap;
	private final Map<String, String> routes;

	ComponentRoutes(Path file, int cap) {
		this.file = file;
		this.cap = cap;
		this.routes = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
				return size() > ComponentRoutes.this.cap;
			}
		};
		load();
	}

	private void load() {
		if (!Files.exists(file))
			return;
		try {
			JsonElement root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
			if (root != null && root.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
					if (entry.getValue().isJsonPrimitive())
						routes.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read {}: {}", file, e.getMessage());
		}
	}

	private void save() {
		JsonObject root = new JsonObject();
		routes.forEach(root::addProperty);
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Could not save {}: {}", file, e.getMessage());
		}
	}

	synchronized void record(String messageId, String server) {
		routes.put(messageId, server);
		save();
	}

	synchronized @Nullable String route(String messageId) {
		return routes.get(messageId);
	}

	synchronized int size() {
		return routes.size();
	}

	synchronized void forget(String server) {
		if (routes.values().removeIf(server::equals))
			save();
	}
}
