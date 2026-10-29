package io.github.itsnotethann.skord.shared;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.discord.DiscordError;
import com.google.gson.JsonPrimitive;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.RefCodec;
import io.github.itsnotethann.skord.rest.KeyedResult;
import org.jetbrains.annotations.Nullable;

public final class SharedValues {

	private static final String SEPARATOR = "::";

	private SharedValues() {}

	public static boolean allowed(Class<?> type) {
		return type == String.class || type == Number.class || type == Long.class || type == Integer.class
			|| type == Double.class || type == Boolean.class || DiscordRef.class.isAssignableFrom(type);
	}

	public static JsonElement toJson(Object[] values, boolean single) {
		if (single)
			return values.length == 0 ? JsonNull.INSTANCE : element(values[0]);
		JsonArray array = new JsonArray();
		for (Object value : values)
			array.add(element(value));
		return array;
	}

	private static JsonElement element(@Nullable Object value) {
		return switch (value) {
			case null -> JsonNull.INSTANCE;
			case String text -> new JsonPrimitive(text);
			case Boolean bool -> new JsonPrimitive(bool);
			case Byte number -> new JsonPrimitive(number.longValue());
			case Short number -> new JsonPrimitive(number.longValue());
			case Integer number -> new JsonPrimitive(number.longValue());
			case Long number -> new JsonPrimitive(number);
			case Number number -> new JsonPrimitive(number.doubleValue());
			case DiscordRef ref -> RefCodec.toJson(ref);
			default -> new JsonPrimitive(String.valueOf(value));
		};
	}

	public static Object[] fromJson(@Nullable JsonElement element, Class<?> type, boolean single, String what) {
		List<Object> out = new ArrayList<>();
		if (element != null && !element.isJsonNull()) {
			if (element.isJsonArray()) {
				if (single && element.getAsJsonArray().size() != 1)
					throw new IllegalArgumentException(what + " takes one value");
				for (JsonElement item : element.getAsJsonArray())
					out.add(scalar(item, type, what));
			} else {
				out.add(scalar(element, type, what));
			}
		}
		Object[] typed = (Object[]) Array.newInstance(type, out.size());
		for (int i = 0; i < typed.length; i++)
			typed[i] = out.get(i);
		return typed;
	}

	private static Object scalar(JsonElement element, Class<?> type, String what) {
		if (type == String.class) {
			if (!element.isJsonPrimitive())
				throw new IllegalArgumentException(what + " expects text");
			return element.getAsString();
		}
		if (type == Boolean.class) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
				throw new IllegalArgumentException(what + " expects true or false");
			return element.getAsBoolean();
		}
		if (type == Number.class || type == Long.class || type == Integer.class || type == Double.class) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
				throw new IllegalArgumentException(what + " expects a number");
			double value = element.getAsDouble();
			if (type == Double.class)
				return value;
			if (type == Integer.class)
				return (int) value;
			if (type == Long.class || value == Math.rint(value) && Math.abs(value) < 9e15)
				return (long) value;
			return value;
		}
		DiscordRef ref = RefCodec.fromJson(element);
		if (ref == null || !type.isInstance(ref))
			throw new IllegalArgumentException(what + " expects a " + type.getSimpleName().replace("Ref", "").toLowerCase());
		return ref;
	}

	public static JsonElement result(Object @Nullable [] values, boolean single) {
		return values == null ? JsonNull.INSTANCE : toJson(values, single);
	}

	public static KeyedResult toSkript(@Nullable JsonElement data) {
		return toSkript("", data);
	}

	private static KeyedResult toSkript(String prefix, @Nullable JsonElement data) {
		if (data == null || data.isJsonNull())
			return KeyedResult.EMPTY;
		List<String> keys = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		if (data.isJsonArray()) {
			int index = 1;
			for (JsonElement item : data.getAsJsonArray())
				add(prefix + index++, item, keys, values);
		} else {
			add(prefix.isEmpty() ? "1" : prefix, data, keys, values);
		}
		return new KeyedResult(keys.toArray(String[]::new), values.toArray());
	}

	private static void add(String key, JsonElement item, List<String> keys, List<Object> values) {
		Object value = RefCodec.valueFromJson(item);
		if (value == null || value instanceof Map || value instanceof List)
			return;
		keys.add(key);
		values.add(value);
	}

	public static JsonObject outcomes(Map<String, SharedCalls.Outcome> outcomes) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, SharedCalls.Outcome> entry : new TreeMap<>(outcomes).entrySet()) {
			JsonObject item = new JsonObject();
			SharedCalls.Outcome outcome = entry.getValue();
			if (outcome.error() != null) {
				JsonObject error = new JsonObject();
				error.addProperty("type", outcome.error().type().label());
				error.addProperty("message", outcome.error().message());
				if (outcome.error().scope() != null)
					error.addProperty("scope", outcome.error().scope());
				item.add("error", error);
			} else {
				item.add("data", outcome.data() == null ? JsonNull.INSTANCE : outcome.data());
			}
			out.add(entry.getKey(), item);
		}
		return out;
	}

	public static Map<String, SharedCalls.Outcome> outcomes(@Nullable JsonElement wire) {
		Map<String, SharedCalls.Outcome> out = new TreeMap<>();
		if (wire == null || !wire.isJsonObject())
			return out;
		for (Map.Entry<String, JsonElement> entry : wire.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonObject())
				continue;
			JsonObject item = entry.getValue().getAsJsonObject();
			if (item.has("error") && item.get("error").isJsonObject()) {
				JsonObject error = item.getAsJsonObject("error");
				DiscordError.Type type = DiscordError.Type.ofLabel(error.has("type") ? error.get("type").getAsString() : "");
				out.put(entry.getKey(), new SharedCalls.Outcome(null, new DiscordError(type == null
					? DiscordError.Type.ERROR : type, error.has("message") ? error.get("message").getAsString() : "",
					null, 0, error.has("scope") ? error.get("scope").getAsString() : null)));
			} else {
				out.put(entry.getKey(), new SharedCalls.Outcome(item.get("data"), null));
			}
		}
		return out;
	}

	public static KeyedResult perServer(Map<String, JsonElement> answers) {
		List<String> keys = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : new TreeMap<>(answers).entrySet()) {
			JsonElement data = entry.getValue();
			if (data == null || data.isJsonNull())
				continue;
			if (data.isJsonArray()) {
				int index = 1;
				for (JsonElement item : data.getAsJsonArray())
					add(entry.getKey() + SEPARATOR + index++, item, keys, values);
			} else {
				add(entry.getKey(), data, keys, values);
			}
		}
		return new KeyedResult(keys.toArray(String[]::new), values.toArray());
	}
}
