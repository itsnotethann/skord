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

final class FilePlayerLinks implements PlayerLinks {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");
	private static final Gson GSON = new Gson();

	private final Path file;
	private final Map<String, String> byDiscord = new LinkedHashMap<>();
	private boolean loaded;

	FilePlayerLinks(Path file) {
		this.file = file;
	}

	private void load() {
		if (loaded)
			return;
		loaded = true;
		if (!Files.exists(file))
			return;
		try {
			JsonElement root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
			if (root != null && root.isJsonObject())
				root.getAsJsonObject().entrySet().forEach(entry -> byDiscord.put(entry.getKey(), entry.getValue().getAsString()));
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Ignoring {}: {}", file, e.getMessage());
		}
	}

	private void save() {
		JsonObject root = new JsonObject();
		byDiscord.forEach(root::addProperty);
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Could not save {}: {}", file, e.getMessage());
		}
	}

	@Override
	public synchronized @Nullable String uuid(String discordId) {
		load();
		return byDiscord.get(discordId);
	}

	@Override
	public synchronized void link(String discordId, String uuid) {
		load();
		byDiscord.put(discordId, uuid);
		save();
	}

	@Override
	public synchronized void unlink(String discordId) {
		load();
		if (byDiscord.remove(discordId) != null)
			save();
	}

	@Override
	public synchronized void clearMemory() {
		byDiscord.clear();
		loaded = false;
	}
}
