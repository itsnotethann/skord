package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.FrameCodec;
import io.github.itsnotethann.skord.protocol.SpecJson;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each server's last accepted commands and last-seen time, in {@code .skord/servers/<name>.json}. They are registered again at boot,
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
		JsonObject root = read(server);
		root.add("commands", array);
		write(server, root, "commands");
	}

	void saveHeld(String server, List<Frame.EventSpec> specs) {
		JsonArray array = new JsonArray();
		for (Frame.EventSpec spec : specs) {
			JsonObject entry = new JsonObject();
			entry.addProperty("kind", spec.kind());
			entry.add("filters", spec.filters());
			array.add(entry);
		}
		JsonObject root = read(server);
		root.add("held", array);
		write(server, root, "held subscriptions");
	}

	List<Frame.EventSpec> loadHeld(String server) {
		JsonElement held = read(server).get("held");
		List<Frame.EventSpec> out = new ArrayList<>();
		if (held != null && held.isJsonArray()) {
			for (JsonElement entry : held.getAsJsonArray()) {
				if (!entry.isJsonObject() || !entry.getAsJsonObject().has("kind"))
					continue;
				JsonObject object = entry.getAsJsonObject();
				out.add(new Frame.EventSpec(object.get("kind").getAsString(),
					object.has("filters") && object.get("filters").isJsonObject() ? object.getAsJsonObject("filters")
						: new JsonObject(), true));
			}
		}
		return out;
	}

	void saveLastSeen(String server, Instant time) {
		JsonObject root = read(server);
		root.addProperty("lastSeen", time.toString());
		write(server, root, "last seen time");
	}

	@Nullable Instant loadLastSeen(String server) {
		JsonElement value = read(server).get("lastSeen");
		if (value == null || !value.isJsonPrimitive())
			return null;
		try {
			return Instant.parse(value.getAsString());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private JsonObject read(String server) {
		Path file = file(server);
		if (!Files.exists(file))
			return new JsonObject();
		try {
			JsonElement root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
			return root != null && root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Ignoring {}: {}", file, e.getMessage());
			return new JsonObject();
		}
	}

	private void write(String server, JsonObject root, String what) {
		try {
			Files.createDirectories(directory);
			Path file = file(server);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("Could not save {}'s {}; a restart will lose it until it reconnects: {}", server, what,
				e.getMessage());
		}
	}

	/** The saved commands, or an empty list if there are none or the file can't be read. */
	List<CommandSpec> load(String server) {
		Path file = file(server);
		if (!Files.exists(file))
			return List.of();
		try {
			JsonElement commands = read(server).get("commands");
			List<CommandSpec> out = new ArrayList<>();
			if (commands != null && commands.isJsonArray()) {
				for (JsonElement entry : commands.getAsJsonArray())
					out.add(SpecJson.fromJson(entry));
			}
			return out;
		} catch (RuntimeException | FrameCodec.FrameException e) {
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
