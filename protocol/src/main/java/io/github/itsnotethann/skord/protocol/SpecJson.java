package io.github.itsnotethann.skord.protocol;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A {@link CommandSpec} as JSON: what travels in a {@code commands} frame and what the bot keeps on disk. */
public final class SpecJson {

	private SpecJson() {}

	public static JsonObject toJson(CommandSpec spec) {
		JsonObject out = new JsonObject();
		JsonArray path = new JsonArray();
		spec.path().forEach(path::add);
		out.add("path", path);
		out.addProperty("description", spec.description());
		JsonArray permissions = new JsonArray();
		spec.permissions().forEach(permission -> permissions.add(permission.name()));
		out.add("permissions", permissions);
		out.addProperty("guildOnly", spec.guildOnly());
		JsonArray options = new JsonArray();
		for (CommandSpec.Option option : spec.options()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", option.name());
			entry.addProperty("kind", option.kind().name());
			entry.addProperty("required", option.required());
			entry.addProperty("description", option.description());
			options.add(entry);
		}
		out.add("options", options);
		out.addProperty("script", spec.script());
		out.addProperty("line", spec.line());
		return out;
	}

	/** Reads a spec, checking it as strictly as a script's own declaration: a peer can't smuggle a bad name in. */
	public static CommandSpec fromJson(JsonElement element) throws FrameCodec.FrameException {
		if (element == null || !element.isJsonObject())
			throw new FrameCodec.FrameException("a command is a JSON object");
		JsonObject object = element.getAsJsonObject();
		List<String> path = new ArrayList<>();
		for (JsonElement word : array(object, "path"))
			path.add(word.getAsString());
		if (path.isEmpty() || path.size() > 3)
			throw new FrameCodec.FrameException("a command name has one to three words");
		for (String word : path) {
			if (!SpecParser.validName(word))
				throw new FrameCodec.FrameException("'" + word + "' isn't a valid command name");
		}
		EnumSet<DiscordPermission> permissions = EnumSet.noneOf(DiscordPermission.class);
		for (JsonElement name : array(object, "permissions")) {
			try {
				permissions.add(DiscordPermission.valueOf(name.getAsString()));
			} catch (IllegalArgumentException e) {
				throw new FrameCodec.FrameException("unknown permission '" + name.getAsString() + "'");
			}
		}
		SpecParser.Arguments arguments = new SpecParser.Arguments();
		for (JsonElement entry : array(object, "options")) {
			if (!entry.isJsonObject())
				throw new FrameCodec.FrameException("an argument is a JSON object");
			JsonObject option = entry.getAsJsonObject();
			String name = text(option, "name");
			if (!SpecParser.validName(name))
				throw new FrameCodec.FrameException("'" + name + "' isn't a valid argument name");
			ArgKind kind;
			try {
				kind = ArgKind.valueOf(text(option, "kind"));
			} catch (IllegalArgumentException e) {
				throw new FrameCodec.FrameException("unknown argument type '" + text(option, "kind") + "'");
			}
			String error = arguments.add(new CommandSpec.Option(name, kind,
				!option.has("required") || option.get("required").getAsBoolean(), text(option, "description")));
			if (error != null)
				throw new FrameCodec.FrameException(error);
		}
		String description = text(object, "description");
		if (description.isEmpty() || description.length() > 100)
			throw new FrameCodec.FrameException("a description is 1 to 100 characters");
		return new CommandSpec(List.copyOf(path), description, permissions,
			object.has("guildOnly") && object.get("guildOnly").getAsBoolean(), arguments.options(),
			object.has("script") ? object.get("script").getAsString() : "?",
			object.has("line") ? object.get("line").getAsInt() : 0, false);
	}

	private static JsonArray array(JsonObject object, String key) throws FrameCodec.FrameException {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray())
			throw new FrameCodec.FrameException("missing '" + key + "'");
		return value.getAsJsonArray();
	}

	private static String text(JsonObject object, String key) throws FrameCodec.FrameException {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive())
			throw new FrameCodec.FrameException("missing '" + key + "'");
		return value.getAsString();
	}
}
