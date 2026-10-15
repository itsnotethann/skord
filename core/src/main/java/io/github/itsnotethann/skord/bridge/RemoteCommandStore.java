package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import io.github.itsnotethann.skord.protocol.SpecJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each server's last accepted commands, in {@code .skord/servers/<name>.json}. They are registered again at boot,
 * before the server reconnects, so a bot restart doesn't take the server's commands out of the sync and put them
 * back a moment later.
 */
final class RemoteCommandStore {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");
	private static final Gson GSON = new Gson();

	private final Path directory;

	RemoteCommandStore(Path directory) {
		this.directory = directory;
	}

	void save(String server, List<CommandSpec> commands) {
		JsonArray array = new JsonArray();
		commands.forEach(command -> array.add(SpecJson.toJson(command)));
		JsonObject root = new JsonObject();
		root.add("commands", array);
		try {
			Files.createDirectories(directory);
			Path file = file(server);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("Could not save {}'s commands; a restart will lose them until it reconnects: {}", server,
				e.getMessage());
		}
	}

	/** The saved commands, or an empty list if there are none or the file can't be read. */
	List<CommandSpec> load(String server) {
		Path file = file(server);
		if (!Files.exists(file))
			return List.of();
		try {
			JsonElement root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
			List<CommandSpec> commands = new ArrayList<>();
			for (JsonElement entry : root.getAsJsonObject().getAsJsonArray("commands"))
				commands.add(SpecJson.fromJson(entry));
			return commands;
		} catch (IOException | RuntimeException | FrameCodec.FrameException e) {
			LOGGER.warn("Ignoring {}: {}", file, e.getMessage());
			return List.of();
		}
	}

	void delete(String server) {
		try {
			Files.deleteIfExists(file(server));
		} catch (IOException e) {
			LOGGER.warn("Could not delete {}: {}", file(server), e.getMessage());
		}
	}

	private Path file(String server) {
		return directory.resolve(server + ".json");
	}
}
