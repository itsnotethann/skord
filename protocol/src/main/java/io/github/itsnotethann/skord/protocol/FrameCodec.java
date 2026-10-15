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
					object.has("heartbeat") ? object.get("heartbeat").getAsLong() : 15_000, scopes(object));
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
			case "result" -> new Frame.Result(string(object, "id"),
				object.has("ok") && object.get("ok").getAsBoolean(),
				object.has("message") ? object.get("message").getAsString() : null, failure(object),
				object.has("data") ? object.get("data") : null);
			case "subscribe-result" -> {
				List<Frame.SubscribeResult.Rejection> rejected = new ArrayList<>();
				for (JsonElement entry : array(object, "rejected")) {
					if (entry.isJsonObject())
						rejected.add(new Frame.SubscribeResult.Rejection(string(entry.getAsJsonObject(), "kind"),
							string(entry.getAsJsonObject(), "reason")));
				}
				yield new Frame.SubscribeResult(List.copyOf(rejected));
			}
			case "scope" -> new Frame.ScopeUpdate(scopes(object), listens(object));
			case "subscribe" -> {
				List<Frame.EventSpec> events = new ArrayList<>();
				for (JsonElement entry : array(object, "events")) {
					if (!entry.isJsonObject())
						continue;
					JsonObject spec = entry.getAsJsonObject();
					events.add(new Frame.EventSpec(string(spec, "kind"),
						spec.has("filters") && spec.get("filters").isJsonObject()
							? spec.getAsJsonObject("filters") : new JsonObject(),
						spec.has("held") && spec.get("held").getAsBoolean()));
				}
				yield new Frame.Subscribe(List.copyOf(events));
			}
			case "event" -> new Frame.Event(string(object, "id"), string(object, "kind"), data(object),
				object.has("redelivery") && object.get("redelivery").getAsBoolean());
			case "event-ack" -> new Frame.EventAck(string(object, "id"));
			case "action" -> new Frame.Action(string(object, "id"), string(object, "kind"),
				object.has("args") && object.get("args").isJsonObject()
					? object.getAsJsonObject("args") : new JsonObject());
			case "functions" -> {
				List<Frame.FunctionSpec> functions = new ArrayList<>();
				for (JsonElement entry : array(object, "functions")) {
					if (!entry.isJsonObject())
						continue;
					JsonObject function = entry.getAsJsonObject();
					List<Frame.FunctionSpec.Parameter> parameters = new ArrayList<>();
					for (JsonElement item : array(function, "parameters")) {
						if (item.isJsonObject())
							parameters.add(new Frame.FunctionSpec.Parameter(string(item.getAsJsonObject(), "name"),
								string(item.getAsJsonObject(), "type")));
					}
					functions.add(new Frame.FunctionSpec(string(function, "name"), List.copyOf(parameters),
						function.has("returns") ? function.get("returns").getAsString() : null));
				}
				yield new Frame.Functions(List.copyOf(functions));
			}
			case "call" -> new Frame.Call(string(object, "id"), string(object, "function"), args(object),
				object.has("wait") && object.get("wait").getAsBoolean());
			case "call-result" -> new Frame.CallResult(string(object, "id"), data(object), failure(object));
			case "players" -> new Frame.Players(object.has("snapshot") && object.get("snapshot").getAsBoolean(),
				players(object, "joined"), players(object, "left"));
			default -> new Frame.Unknown(type, object);
		};
	}

	private static JsonElement args(JsonObject object) {
		JsonElement value = object.get("args");
		return value == null ? com.google.gson.JsonNull.INSTANCE : value;
	}

	private static JsonArray array(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
	}

	private static List<String> names(JsonObject object, String key) {
		List<String> out = new ArrayList<>();
		for (JsonElement entry : array(object, key)) {
			if (entry.isJsonPrimitive())
				out.add(entry.getAsString());
		}
		return List.copyOf(out);
	}

	private static List<Frame.Players.Player> players(JsonObject object, String key) {
		List<Frame.Players.Player> out = new ArrayList<>();
		for (JsonElement entry : array(object, key)) {
			if (!entry.isJsonObject())
				continue;
			JsonObject player = entry.getAsJsonObject();
			if (player.has("uuid") && player.has("name"))
				out.add(new Frame.Players.Player(player.get("uuid").getAsString(), player.get("name").getAsString()));
		}
		return List.copyOf(out);
	}

	private static java.util.Set<Scope> scopes(JsonObject object) {
		java.util.Set<Scope> out = java.util.EnumSet.noneOf(Scope.class);
		for (String word : names(object, "scope")) {
			Scope scope = Scope.of(word);
			if (scope != null)
				out.add(scope);
		}
		return out;
	}

	private static java.util.Set<Listen> listens(JsonObject object) {
		java.util.Set<Listen> out = java.util.EnumSet.noneOf(Listen.class);
		for (String word : names(object, "listen")) {
			Listen listen = Listen.of(word);
			if (listen != null)
				out.add(listen);
		}
		return out;
	}

	private static Frame.Result.Failure failure(JsonObject object) throws FrameException {
		if (!object.has("error") || !object.get("error").isJsonObject())
			return null;
		JsonObject error = object.getAsJsonObject("error");
		return new Frame.Result.Failure(string(error, "type"), string(error, "message"),
			error.has("code") ? error.get("code").getAsInt() : 0,
			error.has("scope") && !error.get("scope").isJsonNull() ? error.get("scope").getAsString() : null);
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
