package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

public final class RefCodec {

	private RefCodec() {}

	public static JsonObject toJson(DiscordRef ref) {
		JsonObject out = new JsonObject();
		out.addProperty("id", Long.toString(ref.id()));
		String name = ref.name();
		if (name != null)
			out.addProperty("name", name);
		switch (ref) {
			case UserRef user -> out.addProperty("kind", "user");
			case MemberRef member -> {
				out.addProperty("kind", "member");
				out.addProperty("guild", Long.toString(member.guild()));
			}
			case GuildRef guild -> out.addProperty("kind", "guild");
			case ChannelRef channel -> out.addProperty("kind", "channel");
			case CategoryRef category -> out.addProperty("kind", "category");
			case RoleRef role -> out.addProperty("kind", "role");
			case MessageRef message -> {
				out.addProperty("kind", "message");
				out.addProperty("channel", Long.toString(message.channel()));
				if (message.content() != null)
					out.addProperty("content", message.content());
				if (message.display() != null)
					out.addProperty("display", message.display());
			}
		}
		return out;
	}

	public static @Nullable DiscordRef fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject())
			return null;
		JsonObject object = element.getAsJsonObject();
		String kind = text(object, "kind");
		String idText = text(object, "id");
		if (kind == null || idText == null)
			return null;
		long id;
		try {
			id = Long.parseLong(idText);
		} catch (NumberFormatException e) {
			return null;
		}
		String name = text(object, "name");
		return switch (kind) {
			case "user" -> new UserRef(id, name);
			case "member" -> {
				Long guild = number(object, "guild");
				yield guild == null ? null : new MemberRef(id, guild, name);
			}
			case "guild" -> new GuildRef(id, name);
			case "channel" -> new ChannelRef(id, name);
			case "category" -> new CategoryRef(id, name);
			case "role" -> new RoleRef(id, name);
			case "message" -> {
				Long channel = number(object, "channel");
				yield channel == null ? null : new MessageRef(id, channel, text(object, "content"), text(object, "display"));
			}
			default -> null;
		};
	}

	public static JsonElement valueToJson(@Nullable Object value) {
		return switch (value) {
			case null -> JsonNull.INSTANCE;
			case DiscordRef ref -> toJson(ref);
			case String text -> new com.google.gson.JsonPrimitive(text);
			case Number number -> new com.google.gson.JsonPrimitive(number);
			case Boolean flag -> new com.google.gson.JsonPrimitive(flag);
			case Map<?, ?> map -> {
				JsonObject out = new JsonObject();
				for (Map.Entry<?, ?> entry : map.entrySet())
					out.add(String.valueOf(entry.getKey()), valueToJson(entry.getValue()));
				yield out;
			}
			case Iterable<?> items -> {
				JsonArray out = new JsonArray();
				for (Object item : items)
					out.add(valueToJson(item));
				yield out;
			}
			default -> new com.google.gson.JsonPrimitive(String.valueOf(value));
		};
	}

	public static @Nullable Object valueFromJson(@Nullable JsonElement element) {
		if (element == null || element.isJsonNull())
			return null;
		if (element.isJsonPrimitive()) {
			var primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean())
				return primitive.getAsBoolean();
			if (primitive.isNumber()) {
				double value = primitive.getAsDouble();
				return value == Math.rint(value) && Math.abs(value) < 9e15 ? (Object) (long) value : (Object) value;
			}
			return primitive.getAsString();
		}
		if (element.isJsonArray()) {
			List<Object> out = new ArrayList<>();
			for (JsonElement item : element.getAsJsonArray())
				out.add(valueFromJson(item));
			return out;
		}
		DiscordRef ref = fromJson(element);
		if (ref != null)
			return ref;
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			out.put(entry.getKey(), valueFromJson(entry.getValue()));
		return out;
	}

	private static @Nullable String text(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
	}

	private static @Nullable Long number(JsonObject object, String key) {
		String text = text(object, key);
		if (text == null)
			return null;
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
