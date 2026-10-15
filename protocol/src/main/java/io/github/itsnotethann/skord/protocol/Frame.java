package io.github.itsnotethann.skord.protocol;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * One JSON frame on the bridge's WebSocket. Every frame has a {@code type}. Later frames (commands, messages,
 * deliveries) arrive as {@link Unknown} until their own records exist, so an older peer never breaks on a newer one.
 */
public sealed interface Frame {

	String type();

	void write(JsonObject out);

	/** A bot's identity, as the server sees it. */
	record BotInfo(String id, String name) {}

	/** First frame from a server. The secret is never printed: see {@link #toString()}. */
	record Hello(int v, String server, String secret, String platform) implements Frame {

		@Override
		public String type() {
			return "hello";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("v", v);
			out.addProperty("server", server);
			out.addProperty("secret", secret);
			out.addProperty("platform", platform);
		}

		@Override
		public String toString() {
			return "Hello[v=" + v + ", server=" + server + ", secret=<hidden>, platform=" + platform + "]";
		}
	}

	/** @param heartbeat how often, in milliseconds, the server should ping the bot */
	record Welcome(BotInfo bot, long heartbeat, Set<Scope> scope) implements Frame {

		@Override
		public String type() {
			return "welcome";
		}

		@Override
		public void write(JsonObject out) {
			JsonObject botObject = new JsonObject();
			botObject.addProperty("id", bot.id());
			botObject.addProperty("name", bot.name());
			out.add("bot", botObject);
			out.addProperty("heartbeat", heartbeat);
			JsonArray granted = new JsonArray();
			scope.stream().sorted().forEach(item -> granted.add(item.wire));
			out.add("scope", granted);
		}
	}

	/**
	 * The bot refuses the connection, then closes it. {@code bad-secret} also covers a name nobody linked, so a
	 * caller can't probe which names exist.
	 */
	record Denied(String reason) implements Frame {

		public static final String BAD_SECRET = "bad-secret";
		public static final String VERSION = "version";
		public static final String REPLACED = "replaced";
		public static final String REVOKED = "revoked";
		public static final String PROTOCOL = "protocol";

		@Override
		public String type() {
			return "denied";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("reason", reason);
		}
	}

	/** A server's full set of commands, re-sent whole after every reload. The bot replaces the old set. */
	record Commands(List<CommandSpec> commands) implements Frame {

		@Override
		public String type() {
			return "commands";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray array = new JsonArray();
			commands.forEach(command -> array.add(SpecJson.toJson(command)));
			out.add("commands", array);
		}
	}

	/** Which of a server's commands the bot would not take, and why. Commands not listed were accepted. */
	record CommandsResult(List<Rejection> rejected) implements Frame {

		public record Rejection(String command, String reason) {}

		@Override
		public String type() {
			return "commands-result";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray array = new JsonArray();
			for (Rejection rejection : rejected) {
				JsonObject entry = new JsonObject();
				entry.addProperty("command", rejection.command());
				entry.addProperty("reason", rejection.reason());
				array.add(entry);
			}
			out.add("rejected", array);
		}
	}

	/**
	 * Someone ran one of the server's commands. {@code options} holds the argument values: text, numbers and
	 * booleans as themselves, Discord objects as {@code {id, name}}.
	 */
	record Interaction(String id, String command, JsonObject options, @Nullable Ref user, @Nullable Ref member,
					   @Nullable Ref guild, @Nullable Ref channel) implements Frame {

		@Override
		public String type() {
			return "interaction";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("command", command);
			out.add("options", options);
			if (user != null)
				out.add("user", user.toJson());
			if (member != null)
				out.add("member", member.toJson());
			if (guild != null)
				out.add("guild", guild.toJson());
			if (channel != null)
				out.add("channel", channel.toJson());
		}
	}

	/**
	 * Answers an interaction. {@code id} names this request, for its {@link Result}; {@code interaction} is the
	 * interaction being answered. {@code message} is the message to edit, or absent for the original reply.
	 */
	record Respond(String id, String interaction, Action action, @Nullable String text, boolean ephemeral,
				   @Nullable String message) implements Frame {

		public enum Action {
			REPLY("reply"), DEFER("defer"), EDIT("edit"), FOLLOW_UP("follow-up"), DELETE("delete");

			public final String wire;

			Action(String wire) {
				this.wire = wire;
			}

			public static @Nullable Action of(String wire) {
				for (Action action : values()) {
					if (action.wire.equals(wire))
						return action;
				}
				return null;
			}
		}

		@Override
		public String type() {
			return "respond";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("interaction", interaction);
			out.addProperty("action", action.wire);
			if (text != null)
				out.addProperty("text", text);
			out.addProperty("ephemeral", ephemeral);
			if (message != null)
				out.addProperty("message", message);
		}
	}

	/** Sent once the server has run every trigger for an interaction as far as it goes without waiting. */
	record Dispatched(String interaction, boolean paused) implements Frame {

		@Override
		public String type() {
			return "dispatched";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("interaction", interaction);
			out.addProperty("paused", paused);
		}
	}

	/** The outcome of one {@link Respond}: ok with the message's id, or an error. */
	record Result(String id, boolean ok, @Nullable String message, @Nullable Failure error,
				  @Nullable JsonElement data) implements Frame {

		public Result(String id, boolean ok, @Nullable String message, @Nullable Failure error) {
			this(id, ok, message, error, null);
		}

		public record Failure(String type, String message, int code, @Nullable String scope) {

			public Failure(String type, String message, int code) {
				this(type, message, code, null);
			}

			public JsonObject toJson() {
				JsonObject failure = new JsonObject();
				failure.addProperty("type", type);
				failure.addProperty("message", message);
				failure.addProperty("code", code);
				if (scope != null)
					failure.addProperty("scope", scope);
				return failure;
			}
		}

		@Override
		public String type() {
			return "result";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("ok", ok);
			if (message != null)
				out.addProperty("message", message);
			if (error != null)
				out.add("error", error.toJson());
			if (data != null)
				out.add("data", data);
		}
	}

	record EventSpec(String kind, JsonObject filters, boolean held) {}

	record Subscribe(List<EventSpec> events) implements Frame {

		@Override
		public String type() {
			return "subscribe";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray array = new JsonArray();
			for (EventSpec spec : events) {
				JsonObject entry = new JsonObject();
				entry.addProperty("kind", spec.kind());
				entry.add("filters", spec.filters());
				entry.addProperty("held", spec.held());
				array.add(entry);
			}
			out.add("events", array);
		}
	}

	/** Which of a {@link Subscribe}'s events the bot would not forward, and why. The rest were accepted. */
	record SubscribeResult(List<Rejection> rejected) implements Frame {

		public record Rejection(String kind, String reason) {}

		@Override
		public String type() {
			return "subscribe-result";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray array = new JsonArray();
			for (Rejection rejection : rejected) {
				JsonObject entry = new JsonObject();
				entry.addProperty("kind", rejection.kind());
				entry.addProperty("reason", rejection.reason());
				array.add(entry);
			}
			out.add("rejected", array);
		}
	}

	/** The bot changed what this server may do ({@code sk link scope}); sent at once, not at the next connect. */
	record ScopeUpdate(Set<Scope> scope, Set<Listen> listen) implements Frame {

		@Override
		public String type() {
			return "scope";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray granted = new JsonArray();
			scope.stream().sorted().forEach(item -> granted.add(item.wire));
			out.add("scope", granted);
			JsonArray listening = new JsonArray();
			listen.stream().sorted().forEach(item -> listening.add(item.wire));
			out.add("listen", listening);
		}
	}

	record Event(String id, String kind, JsonElement data, boolean redelivery) implements Frame {

		@Override
		public String type() {
			return "event";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("kind", kind);
			out.add("data", data == null ? JsonNull.INSTANCE : data);
			out.addProperty("redelivery", redelivery);
		}
	}

	record EventAck(String id) implements Frame {

		@Override
		public String type() {
			return "event-ack";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
		}
	}

	record Action(String id, String kind, JsonObject args) implements Frame {

		@Override
		public String type() {
			return "action";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("kind", kind);
			out.add("args", args);
		}
	}

	record FunctionSpec(String name, List<Parameter> parameters, @Nullable String returns) {

		public record Parameter(String name, String type) {}
	}

	record Functions(List<FunctionSpec> functions) implements Frame {

		@Override
		public String type() {
			return "functions";
		}

		@Override
		public void write(JsonObject out) {
			JsonArray array = new JsonArray();
			for (FunctionSpec function : functions) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", function.name());
				JsonArray parameters = new JsonArray();
				for (FunctionSpec.Parameter parameter : function.parameters()) {
					JsonObject item = new JsonObject();
					item.addProperty("name", parameter.name());
					item.addProperty("type", parameter.type());
					parameters.add(item);
				}
				entry.add("parameters", parameters);
				if (function.returns() != null)
					entry.addProperty("returns", function.returns());
				array.add(entry);
			}
			out.add("functions", array);
		}
	}

	record Call(String id, String function, JsonElement args, boolean waiting) implements Frame {

		@Override
		public String type() {
			return "call";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("function", function);
			out.add("args", args == null ? JsonNull.INSTANCE : args);
			out.addProperty("wait", waiting);
		}
	}

	record CallResult(String id, JsonElement data, @Nullable Result.Failure error) implements Frame {

		@Override
		public String type() {
			return "call-result";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.add("data", data == null ? JsonNull.INSTANCE : data);
			if (error != null)
				out.add("error", error.toJson());
		}
	}

	record Players(boolean snapshot, List<Player> joined, List<Player> left) implements Frame {

		public record Player(String uuid, String name) {

			JsonObject toJson() {
				JsonObject entry = new JsonObject();
				entry.addProperty("uuid", uuid);
				entry.addProperty("name", name);
				return entry;
			}
		}

		@Override
		public String type() {
			return "players";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("snapshot", snapshot);
			JsonArray added = new JsonArray();
			joined.forEach(player -> added.add(player.toJson()));
			out.add("joined", added);
			JsonArray removed = new JsonArray();
			left.forEach(player -> removed.add(player.toJson()));
			out.add("left", removed);
		}
	}

	/** A frame this version has no record for. Kept whole so it can be passed on or ignored. */
	record Unknown(String type, JsonObject body) implements Frame {

		@Override
		public void write(JsonObject out) {
			body.entrySet().forEach(entry -> out.add(entry.getKey(), entry.getValue()));
		}
	}
}
