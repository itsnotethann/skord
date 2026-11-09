package io.github.itsnotethann.skord.discord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Embeds as they travel on the bridge, Discord's limits on them, and how tests show them.
 * <p>
 * On the wire an {@code embeds} array holds objects with any of {@code title, description, url, color (number),
 * author: {name, icon?, url?}, footer: {text, icon?}, image, thumbnail, timestamp (epoch millis), fields: [{name,
 * value, inline}]}}. As with {@code components}, a missing array leaves a message's embeds as they are and an empty
 * one removes them.
 */
public final class Embeds {

	private Embeds() {}

	/** Colours a script can name, Discord's own palette first. */
	public static final Map<String, Integer> NAMED;

	static {
		Map<String, Integer> named = new LinkedHashMap<>();
		named.put("blurple", 0x5865F2);
		named.put("green", 0x57F287);
		named.put("yellow", 0xFEE75C);
		named.put("fuchsia", 0xEB459E);
		named.put("red", 0xED4245);
		named.put("white", 0xFFFFFF);
		named.put("black", 0x23272A);
		named.put("blue", 0x3498DB);
		named.put("aqua", 0x1ABC9C);
		named.put("orange", 0xE67E22);
		named.put("purple", 0x9B59B6);
		named.put("pink", 0xE91E63);
		named.put("gold", 0xF1C40F);
		named.put("grey", 0x95A5A6);
		named.put("gray", 0x95A5A6);
		named.put("dark red", 0x992D22);
		named.put("dark green", 0x1F8B4C);
		named.put("dark blue", 0x206694);
		named.put("dark purple", 0x71368A);
		named.put("dark orange", 0xA84300);
		named.put("dark grey", 0x607D8B);
		named.put("dark gray", 0x607D8B);
		NAMED = java.util.Collections.unmodifiableMap(named);
	}

	/**
	 * A colour from {@code #5865F2}, {@code 5865f2}, {@code 0x5865F2} or a name in {@link #NAMED}. Null when it is
	 * none of those.
	 */
	public static @Nullable Integer parseColor(String text) {
		String trimmed = text.trim().toLowerCase(Locale.ROOT);
		Integer named = NAMED.get(trimmed);
		if (named != null)
			return named;
		String hex = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed.startsWith("0x") ? trimmed.substring(2) : trimmed;
		if (hex.length() != 6 && hex.length() != 3)
			return null;
		if (hex.length() == 3)
			hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
		try {
			return Integer.parseInt(hex, 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** {@code #rrggbb}. */
	public static String showColor(int color) {
		return String.format(Locale.ROOT, "#%06x", color & 0xFFFFFF);
	}

	/**
	 * The first of Discord's limits these embeds break, as a sentence that names the embed and the limit; null when
	 * they can all be sent in one message.
	 */
	public static @Nullable String problem(List<Embed> embeds) {
		if (embeds.size() > Embed.MAX_PER_MESSAGE)
			return "A message can carry " + Embed.MAX_PER_MESSAGE + " embeds at most, and this one has " + embeds.size();
		int total = 0;
		for (int i = 0; i < embeds.size(); i++) {
			Embed embed = embeds.get(i);
			String which = name(embed, i, embeds.size());
			String capital = Character.toUpperCase(which.charAt(0)) + which.substring(1);
			if (embed.isEmpty())
				return capital + " is empty; give it a title, a description, a field or an image";
			String broken = over("title of " + which, embed.title(), Embed.MAX_TITLE);
			if (broken == null)
				broken = over("description of " + which, embed.description(), Embed.MAX_DESCRIPTION);
			if (broken == null)
				broken = over("author name of " + which, embed.authorName(), Embed.MAX_AUTHOR);
			if (broken == null)
				broken = over("footer of " + which, embed.footerText(), Embed.MAX_FOOTER);
			if (broken == null)
				broken = link("url of " + which, embed.url(), false);
			if (broken == null)
				broken = link("author url of " + which, embed.authorUrl(), false);
			if (broken == null)
				broken = link("author icon of " + which, embed.authorIcon(), true);
			if (broken == null)
				broken = link("footer icon of " + which, embed.footerIcon(), true);
			if (broken == null)
				broken = link("image of " + which, embed.image(), true);
			if (broken == null)
				broken = link("thumbnail of " + which, embed.thumbnail(), true);
			if (broken != null)
				return broken;
			if (embed.fields().size() > Embed.MAX_FIELDS)
				return capital + " has " + embed.fields().size() + " fields, and Discord allows " + Embed.MAX_FIELDS;
			for (Embed.Field field : embed.fields()) {
				if (field.name().isEmpty() || field.value().isEmpty())
					return capital + " has a field with an empty " + (field.name().isEmpty() ? "name" : "value");
				broken = over("name of field '" + cut(field.name()) + "' in " + which, field.name(), Embed.MAX_FIELD_NAME);
				if (broken == null)
					broken = over("value of field '" + cut(field.name()) + "' in " + which, field.value(), Embed.MAX_FIELD_VALUE);
				if (broken != null)
					return broken;
			}
			total += embed.length();
		}
		if (total > Embed.MAX_TOTAL)
			return "The embeds of a message can hold " + Embed.MAX_TOTAL + " characters together, and these hold " + total;
		return null;
	}

	/** How a problem names an embed: by its title, else by its place, in lower case. */
	private static String name(Embed embed, int index, int count) {
		if (embed.title() != null && !embed.title().isEmpty())
			return "embed '" + cut(embed.title()) + "'";
		return count == 1 ? "the embed" : "embed " + (index + 1);
	}

	private static String cut(String text) {
		return text.length() <= 30 ? text : text.substring(0, 27) + "...";
	}

	/** Discord takes http(s) links, and for pictures also {@code attachment://name}. */
	private static @Nullable String link(String what, @Nullable String url, boolean picture) {
		if (url == null || url.startsWith("https://") || url.startsWith("http://")
			|| picture && url.startsWith("attachment://"))
			return null;
		return "The " + what + " is '" + cut(url) + "', which isn't a link; it has to start with https://";
	}

	private static @Nullable String over(String what, @Nullable String text, int max) {
		if (text == null || text.length() <= max)
			return null;
		return "The " + what + " is " + text.length() + " characters, and Discord allows " + max;
	}

	public static JsonArray toJson(List<Embed> embeds) {
		JsonArray out = new JsonArray();
		for (Embed embed : embeds) {
			JsonObject entry = new JsonObject();
			put(entry, "title", embed.title());
			put(entry, "description", embed.description());
			put(entry, "url", embed.url());
			if (embed.color() != null)
				entry.addProperty("color", embed.color());
			if (embed.authorName() != null || embed.authorIcon() != null || embed.authorUrl() != null) {
				JsonObject author = new JsonObject();
				put(author, "name", embed.authorName());
				put(author, "icon", embed.authorIcon());
				put(author, "url", embed.authorUrl());
				entry.add("author", author);
			}
			if (embed.footerText() != null || embed.footerIcon() != null) {
				JsonObject footer = new JsonObject();
				put(footer, "text", embed.footerText());
				put(footer, "icon", embed.footerIcon());
				entry.add("footer", footer);
			}
			put(entry, "image", embed.image());
			put(entry, "thumbnail", embed.thumbnail());
			if (embed.timestamp() != null)
				entry.addProperty("timestamp", embed.timestamp());
			if (!embed.fields().isEmpty()) {
				JsonArray fields = new JsonArray();
				for (Embed.Field field : embed.fields()) {
					JsonObject item = new JsonObject();
					item.addProperty("name", field.name());
					item.addProperty("value", field.value());
					item.addProperty("inline", field.inline());
					fields.add(item);
				}
				entry.add("fields", fields);
			}
			out.add(entry);
		}
		return out;
	}

	/** Null when the element is absent or not an array. Entries that aren't objects, and malformed fields, are left out. */
	public static @Nullable List<Embed> fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonArray())
			return null;
		List<Embed> out = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonObject())
				continue;
			JsonObject entry = item.getAsJsonObject();
			try {
				JsonObject author = object(entry, "author");
				JsonObject footer = object(entry, "footer");
				List<Embed.Field> fields = new ArrayList<>();
				if (entry.has("fields") && entry.get("fields").isJsonArray()) {
					for (JsonElement field : entry.getAsJsonArray("fields")) {
						try {
							JsonObject object = field.getAsJsonObject();
							fields.add(new Embed.Field(object.get("name").getAsString(), object.get("value").getAsString(),
								object.has("inline") && object.get("inline").getAsBoolean()));
						} catch (RuntimeException malformed) {
							// Left out, like a malformed component.
						}
					}
				}
				out.add(new Embed(text(entry, "title"), text(entry, "description"), text(entry, "url"),
					entry.has("color") && !entry.get("color").isJsonNull() ? entry.get("color").getAsInt() : null,
					author == null ? null : text(author, "name"), author == null ? null : text(author, "icon"),
					author == null ? null : text(author, "url"), footer == null ? null : text(footer, "text"),
					footer == null ? null : text(footer, "icon"), text(entry, "image"), text(entry, "thumbnail"),
					entry.has("timestamp") && !entry.get("timestamp").isJsonNull() ? entry.get("timestamp").getAsLong() : null,
					fields));
			} catch (RuntimeException malformed) {
				// Skipped: an embed the bot can't read is left out rather than failing the whole message.
			}
		}
		return out;
	}

	/**
	 * How tests and logs show embeds: {@code [embed title "T" description "D" ... fields {"Name"="Value" inline}]},
	 * each part only when it is set, in a fixed order. {@code [no embeds]} for none.
	 */
	public static String shown(List<Embed> embeds) {
		if (embeds.isEmpty())
			return "[no embeds]";
		StringBuilder out = new StringBuilder("[");
		for (Embed embed : embeds) {
			if (out.length() > 1)
				out.append(", ");
			out.append("embed");
			quoted(out, "title", embed.title());
			quoted(out, "description", embed.description());
			plain(out, "url", embed.url());
			if (embed.color() != null)
				out.append(" colour ").append(showColor(embed.color()));
			quoted(out, "author", embed.authorName());
			plain(out, "author icon", embed.authorIcon());
			plain(out, "author url", embed.authorUrl());
			quoted(out, "footer", embed.footerText());
			plain(out, "footer icon", embed.footerIcon());
			plain(out, "image", embed.image());
			plain(out, "thumbnail", embed.thumbnail());
			if (embed.timestamp() != null)
				out.append(" timestamp ").append(Instant.ofEpochMilli(embed.timestamp()));
			if (!embed.fields().isEmpty()) {
				out.append(" fields {");
				boolean first = true;
				for (Embed.Field field : embed.fields()) {
					if (!first)
						out.append(", ");
					first = false;
					out.append('"').append(field.name()).append("\"=\"").append(field.value()).append('"');
					if (field.inline())
						out.append(" inline");
				}
				out.append('}');
			}
		}
		return out.append(']').toString();
	}

	private static void quoted(StringBuilder out, String label, @Nullable String value) {
		if (value != null)
			out.append(' ').append(label).append(" \"").append(value).append('"');
	}

	private static void plain(StringBuilder out, String label, @Nullable String value) {
		if (value != null)
			out.append(' ').append(label).append(' ').append(value);
	}

	private static void put(JsonObject object, String key, @Nullable String value) {
		if (value != null)
			object.addProperty(key, value);
	}

	private static @Nullable JsonObject object(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
	}

	private static @Nullable String text(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
	}
}
