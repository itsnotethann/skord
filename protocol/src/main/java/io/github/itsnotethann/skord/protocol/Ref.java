package io.github.itsnotethann.skord.protocol;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * A Discord object as a game server sees it: an id and a name, and nothing else. There is no JDA on the server,
 * so anything that needs more goes back to the bot.
 */
public record Ref(String id, String name) {

	public JsonObject toJson() {
		JsonObject out = new JsonObject();
		out.addProperty("id", id);
		out.addProperty("name", name);
		return out;
	}

	static @Nullable Ref fromJson(@Nullable com.google.gson.JsonElement element) {
		if (element == null || !element.isJsonObject())
			return null;
		JsonObject object = element.getAsJsonObject();
		if (!object.has("id"))
			return null;
		return new Ref(object.get("id").getAsString(),
			object.has("name") && !object.get("name").isJsonNull() ? object.get("name").getAsString() : "");
	}

	@Override
	public String toString() {
		return name.isEmpty() ? id : name;
	}
}
