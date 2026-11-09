package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Modals as they travel on the bridge: {@code {id, title, inputs: [{id, label, style, placeholder?, value?,
 * min?, max?, required}]}}, with {@code style} {@code short} or {@code paragraph}. A submit's values travel as a
 * plain object of input id to value ({@link #valuesToJson}).
 */
public final class Modals {

	private Modals() {}

	public static JsonObject toJson(Modal modal) {
		JsonObject out = new JsonObject();
		out.addProperty("id", modal.id());
		out.addProperty("title", modal.title());
		JsonArray inputs = new JsonArray();
		for (Modal.TextInput input : modal.inputs()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("id", input.id());
			entry.addProperty("label", input.label());
			entry.addProperty("style", input.paragraph() ? "paragraph" : "short");
			if (input.placeholder() != null)
				entry.addProperty("placeholder", input.placeholder());
			if (input.value() != null)
				entry.addProperty("value", input.value());
			if (input.minLength() >= 0)
				entry.addProperty("min", input.minLength());
			if (input.maxLength() >= 0)
				entry.addProperty("max", input.maxLength());
			entry.addProperty("required", input.required());
			inputs.add(entry);
		}
		out.add("inputs", inputs);
		return out;
	}

	/** Null when the element is absent or malformed; inputs that don't parse are left out. */
	public static @Nullable Modal fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject())
			return null;
		JsonObject object = element.getAsJsonObject();
		try {
			List<Modal.TextInput> inputs = new ArrayList<>();
			if (object.has("inputs") && object.get("inputs").isJsonArray()) {
				for (JsonElement item : object.getAsJsonArray("inputs")) {
					try {
						JsonObject entry = item.getAsJsonObject();
						inputs.add(new Modal.TextInput(entry.get("id").getAsString(), entry.get("label").getAsString(),
							"paragraph".equals(text(entry, "style")), text(entry, "placeholder"), text(entry, "value"),
							entry.has("min") ? entry.get("min").getAsInt() : -1,
							entry.has("max") ? entry.get("max").getAsInt() : -1,
							!entry.has("required") || entry.get("required").getAsBoolean()));
					} catch (RuntimeException malformed) {
						// Left out, like a malformed component.
					}
				}
			}
			return new Modal(object.get("id").getAsString(), object.get("title").getAsString(), inputs);
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	public static JsonObject valuesToJson(Map<String, String> values) {
		JsonObject out = new JsonObject();
		values.forEach(out::addProperty);
		return out;
	}

	/** Null when absent or not an object. Entries that aren't text are left out. */
	public static @Nullable Map<String, String> valuesFromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject())
			return null;
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			if (entry.getValue().isJsonPrimitive())
				out.put(entry.getKey(), entry.getValue().getAsString());
		}
		return out;
	}

	/**
	 * How tests and logs show a modal: {@code report "Report a player" {short who:"Who" required, paragraph
	 * details:"What happened" 10-1000 optional placeholder "..." value "..."}}. A length shows as {@code min-max},
	 * with {@code 0} or {@code 4000} filling a side that isn't set; none shows when neither is.
	 */
	public static String shown(Modal modal) {
		StringBuilder out = new StringBuilder(modal.id()).append(" \"").append(modal.title()).append("\" {");
		boolean first = true;
		for (Modal.TextInput input : modal.inputs()) {
			if (!first)
				out.append(", ");
			first = false;
			out.append(input.paragraph() ? "paragraph " : "short ").append(input.id()).append(":\"")
				.append(input.label()).append('"');
			if (input.minLength() >= 0 || input.maxLength() >= 0)
				out.append(' ').append(Math.max(0, input.minLength())).append('-')
					.append(input.maxLength() >= 0 ? input.maxLength() : Modal.MAX_LENGTH);
			out.append(input.required() ? " required" : " optional");
			if (input.placeholder() != null)
				out.append(" placeholder \"").append(input.placeholder()).append('"');
			if (input.value() != null)
				out.append(" value \"").append(input.value()).append('"');
		}
		return out.append('}').toString();
	}

	private static @Nullable String text(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
	}
}
