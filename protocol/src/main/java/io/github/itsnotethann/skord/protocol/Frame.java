package io.github.itsnotethann.skord.protocol;

import java.util.List;

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
	record Welcome(BotInfo bot, long heartbeat) implements Frame {

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
			REPLY("reply"), DEFER("defer"), EDIT("edit"), FOLLOW_UP("follow-up");

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
	record Result(String id, boolean ok, @Nullable String message, @Nullable Failure error) implements Frame {

		public record Failure(String type, String message, int code) {}

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
			if (error != null) {
				JsonObject failure = new JsonObject();
				failure.addProperty("type", error.type());
				failure.addProperty("message", error.message());
				failure.addProperty("code", error.code());
				out.add("error", failure);
			}
		}
	}

	/** A named event with data, either way. Fire and forget over a live connection. {@code data} is any JSON. */
	record Message(String name, JsonElement data) implements Frame {

		@Override
		public String type() {
			return "message";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("name", name);
			out.add("data", data == null ? JsonNull.INSTANCE : data);
		}
	}

	/** A named call that expects a {@link Response} with the same {@code id}. */
	record Request(String id, String name, JsonElement data) implements Frame {

		@Override
		public String type() {
			return "request";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.addProperty("name", name);
			out.add("data", data == null ? JsonNull.INSTANCE : data);
		}
	}

	/** The answer to a {@link Request}: its data, or an error. */
	record Response(String id, JsonElement data, @Nullable Result.Failure error) implements Frame {

		@Override
		public String type() {
			return "response";
		}

		@Override
		public void write(JsonObject out) {
			out.addProperty("id", id);
			out.add("data", data == null ? JsonNull.INSTANCE : data);
			if (error != null) {
				JsonObject failure = new JsonObject();
				failure.addProperty("type", error.type());
				failure.addProperty("message", error.message());
				failure.addProperty("code", error.code());
				out.add("error", failure);
			}
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
