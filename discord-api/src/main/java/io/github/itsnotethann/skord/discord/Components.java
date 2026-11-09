package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Components as they travel on the bridge: an array of objects with a {@code type}. A button is
 * {@code {type: "button", id, label, style, disabled, url?}}; a select menu is
 * {@code {type: "select", id, placeholder?, min, max, disabled, options: [{label, value, description?, emoji?,
 * default}]}}. A missing {@code type} means a button, which is what peers from before select menus send.
 */
public final class Components {

	private Components() {}

	public static JsonArray toJson(List<? extends MessageComponent> components) {
		JsonArray out = new JsonArray();
		for (MessageComponent component : components) {
			JsonObject entry = new JsonObject();
			switch (component) {
				case Button button -> {
					entry.addProperty("type", "button");
					entry.addProperty("id", button.id());
					entry.addProperty("label", button.label());
					entry.addProperty("style", button.style().name().toLowerCase(Locale.ROOT));
					entry.addProperty("disabled", button.disabled());
					if (button.url() != null)
						entry.addProperty("url", button.url());
				}
				case SelectMenu menu -> {
					entry.addProperty("type", "select");
					entry.addProperty("id", menu.id());
					if (menu.placeholder() != null)
						entry.addProperty("placeholder", menu.placeholder());
					entry.addProperty("min", menu.minValues());
					entry.addProperty("max", menu.maxValues());
					entry.addProperty("disabled", menu.disabled());
					JsonArray options = new JsonArray();
					for (SelectMenu.Option option : menu.options()) {
						JsonObject item = new JsonObject();
						item.addProperty("label", option.label());
						item.addProperty("value", option.value());
						if (option.description() != null)
							item.addProperty("description", option.description());
						if (option.emoji() != null)
							item.addProperty("emoji", option.emoji());
						item.addProperty("default", option.isDefault());
						options.add(item);
					}
					entry.add("options", options);
				}
			}
			out.add(entry);
		}
		return out;
	}

	/** Null when the element is absent or not an array; entries that don't parse, or of unknown type, are left out. */
	public static @Nullable List<MessageComponent> fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonArray())
			return null;
		List<MessageComponent> out = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonObject())
				continue;
			JsonObject entry = item.getAsJsonObject();
			try {
				String type = entry.has("type") ? entry.get("type").getAsString() : "button";
				switch (type) {
					case "button" -> out.add(button(entry));
					case "select" -> out.add(menu(entry));
					default -> {} // A newer peer's component this side can't show: skipped, like a malformed one.
				}
			} catch (RuntimeException malformed) {
				// Skipped: a component the bot can't read is not sent rather than failing the whole message.
			}
		}
		return out;
	}

	private static Button button(JsonObject entry) {
		Button.Style style = Button.Style.ofWord(entry.get("style").getAsString());
		return new Button(entry.has("id") ? entry.get("id").getAsString() : "", entry.get("label").getAsString(),
			style, flag(entry, "disabled"), text(entry, "url"));
	}

	private static SelectMenu menu(JsonObject entry) {
		List<SelectMenu.Option> options = new ArrayList<>();
		if (entry.has("options") && entry.get("options").isJsonArray()) {
			for (JsonElement element : entry.getAsJsonArray("options")) {
				JsonObject option = element.getAsJsonObject();
				options.add(new SelectMenu.Option(option.get("label").getAsString(), option.get("value").getAsString(),
					text(option, "description"), text(option, "emoji"), flag(option, "default")));
			}
		}
		return new SelectMenu(entry.get("id").getAsString(), text(entry, "placeholder"), options,
			entry.has("min") ? entry.get("min").getAsInt() : 1, entry.has("max") ? entry.get("max").getAsInt() : 1,
			flag(entry, "disabled"));
	}

	private static @Nullable String text(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
	}

	private static boolean flag(JsonObject object, String key) {
		return object.has(key) && object.get(key).getAsBoolean();
	}

	/**
	 * How tests and logs show components. Buttons read {@code success verify:I agree} or
	 * {@code link https://x:Docs}; a menu reads {@code select role "Pick" 1-2 {a:Apple*, b:Banana}}, with
	 * {@code *} after a default option, an emoji before its value and a description in brackets after its label.
	 * {@code [no components]} for none.
	 */
	public static String shown(List<? extends MessageComponent> components) {
		if (components.isEmpty())
			return "[no components]";
		StringBuilder out = new StringBuilder("[");
		for (MessageComponent component : components) {
			if (out.length() > 1)
				out.append(", ");
			switch (component) {
				case Button button -> out.append(button.style().name().toLowerCase(Locale.ROOT)).append(' ')
					.append(button.isLink() ? button.url() : button.id()).append(':').append(button.label());
				case SelectMenu menu -> {
					out.append("select ").append(menu.id());
					if (menu.placeholder() != null)
						out.append(" \"").append(menu.placeholder()).append('"');
					out.append(' ').append(menu.effectiveMin()).append('-').append(menu.effectiveMax()).append(" {");
					boolean first = true;
					for (SelectMenu.Option option : menu.options()) {
						if (!first)
							out.append(", ");
						first = false;
						if (option.emoji() != null)
							out.append(option.emoji()).append(' ');
						out.append(option.value()).append(':').append(option.label());
						if (option.description() != null)
							out.append(" (").append(option.description()).append(')');
						if (option.isDefault())
							out.append('*');
					}
					out.append('}');
				}
			}
			out.append(component.disabled() ? " (disabled)" : "");
		}
		return out.append(']').toString();
	}
}
