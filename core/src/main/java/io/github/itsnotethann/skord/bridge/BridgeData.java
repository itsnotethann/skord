package io.github.itsnotethann.skord.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.njol.skript.registrations.Classes;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.itsnotethann.skord.protocol.Ref;
import io.github.itsnotethann.skord.rest.KeyedResult;
import org.jetbrains.annotations.Nullable;

/**
 * Bridge data between JSON and Skript. A scalar is one value; a list is values keyed {@code 1, 2, ...}; an object
 * is values keyed by field, nested fields joined with {@code ::} like Skript's own nested list variables.
 * Going the other way, a list variable with non-numeric keys becomes an object.
 */
public final class BridgeData {

	private static final String SEPARATOR = "::";

	private BridgeData() {}

	public static KeyedResult toSkript(JsonElement data) {
		if (data == null || data.isJsonNull())
			return KeyedResult.EMPTY;
		List<String> keys = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		if (data.isJsonPrimitive()) {
			keys.add("1");
			values.add(scalar(data.getAsJsonPrimitive()));
		} else {
			flatten("", data, keys, values);
		}
		return new KeyedResult(keys.toArray(String[]::new), values.toArray());
	}

	private static void flatten(String prefix, JsonElement element, List<String> keys, List<Object> values) {
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
				flatten(prefix + entry.getKey() + SEPARATOR, entry.getValue(), keys, values);
		} else if (element.isJsonArray()) {
			int index = 1;
			for (JsonElement item : element.getAsJsonArray())
				flatten(prefix + index++ + SEPARATOR, item, keys, values);
		} else if (element.isJsonPrimitive()) {
			keys.add(prefix.endsWith(SEPARATOR) ? prefix.substring(0, prefix.length() - SEPARATOR.length()) : prefix);
			values.add(scalar(element.getAsJsonPrimitive()));
		}
	}

	private static Object scalar(JsonPrimitive primitive) {
		if (primitive.isBoolean())
			return primitive.getAsBoolean();
		if (primitive.isNumber()) {
			double value = primitive.getAsDouble();
			if (value == Math.rint(value) && Math.abs(value) < 9e15)
				return (long) value;
			return value;
		}
		return primitive.getAsString();
	}

	/**
	 * @param keys the keys of {@code values}, or null if they have none. Keys other than {@code 1..n} make an
	 *             object.
	 */
	public static JsonElement fromSkript(Object[] values, @Nullable String[] keys) {
		if (values.length == 0)
			return JsonNull.INSTANCE;
		if (keys == null || keys.length != values.length || isIndexed(keys)) {
			if (values.length == 1)
				return element(values[0]);
			JsonArray array = new JsonArray();
			for (Object value : values)
				array.add(element(value));
			return array;
		}
		JsonObject root = new JsonObject();
		for (int i = 0; i < values.length; i++) {
			String[] path = keys[i].split(SEPARATOR);
			JsonObject at = root;
			for (int step = 0; step < path.length - 1; step++) {
				JsonElement next = at.get(path[step]);
				if (next == null || !next.isJsonObject()) {
					next = new JsonObject();
					at.add(path[step], next);
				}
				at = next.getAsJsonObject();
			}
			at.add(path[path.length - 1], element(values[i]));
		}
		return root;
	}

	private static boolean isIndexed(String[] keys) {
		for (int i = 0; i < keys.length; i++) {
			if (!keys[i].equals(String.valueOf(i + 1)))
				return false;
		}
		return true;
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
			default -> {
				Ref ref = BridgeRouter.ref(value);
				yield ref != null ? ref.toJson() : new JsonPrimitive(Classes.toString(value));
			}
		};
	}
}
