package io.github.itsnotethann.skord.protocol;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/** Frames to and from their JSON text. */
public final class FrameCodec {

	/** The protocol version in {@code hello}. A breaking change to a frame bumps it. */
	public static final int VERSION = 1;

	/** Frames larger than this are refused outright, whatever they hold. */
	public static final int MAX_FRAME_CHARS = 1 << 20;

	private static final Gson GSON = new Gson();

	private FrameCodec() {}

	public static class FrameException extends Exception {

		public FrameException(String message) {
			super(message);
		}
	}

	public static String encode(Frame frame) {
		JsonObject out = new JsonObject();
		out.addProperty("type", frame.type());
		frame.write(out);
		return GSON.toJson(out);
	}

	public static Frame decode(String text) throws FrameException {
		if (text.length() > MAX_FRAME_CHARS)
			throw new FrameException("frame too large");
		JsonObject object;
		try {
			JsonElement parsed = GSON.fromJson(text, JsonElement.class);
			if (parsed == null || !parsed.isJsonObject())
				throw new FrameException("a frame is a JSON object");
			object = parsed.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new FrameException("not JSON");
		}
		String type = string(object, "type");
		return switch (type) {
			case "hello" -> new Frame.Hello(integer(object, "v"), string(object, "server"), string(object, "secret"),
				string(object, "platform"));
			case "welcome" -> {
				JsonObject bot = object.has("bot") && object.get("bot").isJsonObject()
					? object.getAsJsonObject("bot") : null;
				if (bot == null)
					throw new FrameException("welcome has no bot");
				yield new Frame.Welcome(new Frame.BotInfo(string(bot, "id"), string(bot, "name")),
					object.has("heartbeat") ? object.get("heartbeat").getAsLong() : 15_000);
			}
			case "denied" -> new Frame.Denied(string(object, "reason"));
			case "commands" -> {
				List<CommandSpec> commands = new ArrayList<>();
				JsonElement list = object.get("commands");
				if (list == null || !list.isJsonArray())
					throw new FrameException("commands has no list");
				for (JsonElement entry : list.getAsJsonArray())
					commands.add(SpecJson.fromJson(entry));
				yield new Frame.Commands(List.copyOf(commands));
			}
			case "commands-result" -> {
				List<Frame.CommandsResult.Rejection> rejected = new ArrayList<>();
				JsonElement list = object.get("rejected");
				if (list != null && list.isJsonArray()) {
					for (JsonElement entry : list.getAsJsonArray()) {
						if (entry.isJsonObject())
							rejected.add(new Frame.CommandsResult.Rejection(string(entry.getAsJsonObject(), "command"),
								string(entry.getAsJsonObject(), "reason")));
					}
				}
				yield new Frame.CommandsResult(List.copyOf(rejected));
			}
			case "interaction" -> new Frame.Interaction(string(object, "id"), string(object, "command"),
				object.has("options") && object.get("options").isJsonObject()
					? object.getAsJsonObject("options") : new com.google.gson.JsonObject(),
				Ref.fromJson(object.get("user")), Ref.fromJson(object.get("member")),
				Ref.fromJson(object.get("guild")), Ref.fromJson(object.get("channel")));
			case "respond" -> {
				Frame.Respond.Action action = Frame.Respond.Action.of(string(object, "action"));
				if (action == null)
					throw new FrameException("unknown action");
				yield new Frame.Respond(string(object, "id"), string(object, "interaction"), action,
					object.has("text") ? object.get("text").getAsString() : null,
					object.has("ephemeral") && object.get("ephemeral").getAsBoolean(),
					object.has("message") ? object.get("message").getAsString() : null);
			}
			case "dispatched" -> new Frame.Dispatched(string(object, "interaction"),
				object.has("paused") && object.get("paused").getAsBoolean());
			case "result" -> {
				Frame.Result.Failure failure = null;
				if (object.has("error") && object.get("error").isJsonObject()) {
					com.google.gson.JsonObject error = object.getAsJsonObject("error");
					failure = new Frame.Result.Failure(string(error, "type"), string(error, "message"),
						error.has("code") ? error.get("code").getAsInt() : 0);
				}
				yield new Frame.Result(string(object, "id"), object.has("ok") && object.get("ok").getAsBoolean(),
					object.has("message") ? object.get("message").getAsString() : null, failure);
			}
			case "message" -> new Frame.Message(string(object, "name"), data(object));
			case "request" -> new Frame.Request(string(object, "id"), string(object, "name"), data(object));
			case "response" -> {
				Frame.Result.Failure failure = null;
				if (object.has("error") && object.get("error").isJsonObject()) {
					JsonObject error = object.getAsJsonObject("error");
					failure = new Frame.Result.Failure(string(error, "type"), string(error, "message"),
						error.has("code") ? error.get("code").getAsInt() : 0);
				}
				yield new Frame.Response(string(object, "id"), data(object), failure);
			}
			default -> new Frame.Unknown(type, object);
		};
	}

	private static JsonElement data(JsonObject object) {
		JsonElement value = object.get("data");
		return value == null ? com.google.gson.JsonNull.INSTANCE : value;
	}

	private static String string(JsonObject object, String key) throws FrameException {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive())
			throw new FrameException("missing '" + key + "'");
		return value.getAsString();
	}

	private static int integer(JsonObject object, String key) throws FrameException {
		try {
			return Integer.parseInt(string(object, key));
		} catch (NumberFormatException e) {
			throw new FrameException("'" + key + "' is not a number");
		}
	}
}
