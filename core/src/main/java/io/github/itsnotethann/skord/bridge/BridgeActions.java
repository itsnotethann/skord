package io.github.itsnotethann.skord.bridge;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordActions;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.FetchProperty;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RefCodec;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.protocol.Scope;
import org.jetbrains.annotations.Nullable;

/**
 * Carries out a server's {@code action} frames through the port, so the bot's real Discord (and testbot's fake)
 * does the work. Which scope each kind needs is fixed here; {@link BridgeRouter} checks it before calling in.
 */
final class BridgeActions {

	private BridgeActions() {}

	static @Nullable Scope scopeOf(String kind) {
		return switch (kind) {
			case "send" -> Scope.SEND;
			case "reply" -> Scope.REPLY;
			case "edit" -> Scope.EDIT;
			case "create-channel" -> Scope.CHANNELS;
			case "fetch", "gateway-ping" -> Scope.READ;
			default -> null;
		};
	}

	static Frame.Result failure(String id, Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null)
			error = error.getCause();
		DiscordError failure = DiscordError.of(error);
		return new Frame.Result(id, false, null, new Frame.Result.Failure(failure.type().label(), failure.message(),
			failure.code(), failure.scope()));
	}

	static Frame.Result outOfScope(String id, Scope missing) {
		return new Frame.Result(id, false, null, new Frame.Result.Failure(DiscordError.Type.OUT_OF_SCOPE.label(),
			"This server's link does not allow '" + missing.wire + "' actions", 0, missing.wire));
	}

	private static Frame.Result invalid(String id, String message) {
		return new Frame.Result(id, false, null, new Frame.Result.Failure(DiscordError.Type.INVALID_INPUT.label(),
			message, 0));
	}

	/** Runs the action on the calling thread's behalf and completes with the {@code result} to send back. */
	static CompletionStage<Frame.Result> execute(Frame.Action frame) {
		DiscordActions actions = Discord.current().actions();
		JsonObject args = frame.args();
		String id = frame.id();
		try {
			CompletionStage<Frame.Result> stage = switch (frame.kind()) {
				case "send" -> {
					ChannelRef channel = ref(args, "channel", ChannelRef.class);
					String text = text(args, "text");
					if (channel == null || text == null)
						yield CompletableFuture.completedFuture(invalid(id, "send needs a channel and text"));
					yield actions.send(channel, text).thenApply(message -> message(id, message));
				}
				case "reply" -> {
					MessageRef message = ref(args, "message", MessageRef.class);
					String text = text(args, "text");
					if (message == null || text == null)
						yield CompletableFuture.completedFuture(invalid(id, "reply needs a message and text"));
					yield actions.reply(message, text).thenApply(sent -> message(id, sent));
				}
				case "edit" -> {
					MessageRef message = ref(args, "message", MessageRef.class);
					String text = text(args, "text");
					if (message == null || text == null)
						yield CompletableFuture.completedFuture(invalid(id, "edit needs a message and text"));
					yield actions.edit(message, text).thenApply(edited -> message(id, edited));
				}
				case "create-channel" -> {
					String name = text(args, "name");
					CategoryRef category = ref(args, "category", CategoryRef.class);
					GuildRef guild = ref(args, "guild", GuildRef.class);
					if (name == null || category == null && guild == null)
						yield CompletableFuture.completedFuture(invalid(id, "create-channel needs a name and a category or guild"));
					CompletionStage<ChannelRef> created = category != null
						? actions.createTextChannel(category, name)
						: actions.createTextChannel(guild, name);
					yield created.thenApply(channel -> new Frame.Result(id, true, Long.toString(channel.id()), null));
				}
				case "fetch" -> {
					DiscordRef target = RefCodec.fromJson(args.get("ref"));
					String property = text(args, "property");
					FetchProperty parsed = null;
					if (property != null) {
						for (FetchProperty candidate : FetchProperty.values()) {
							if (candidate.name().toLowerCase(Locale.ROOT).equals(property))
								parsed = candidate;
						}
					}
					if (target == null || parsed == null)
						yield CompletableFuture.completedFuture(invalid(id, "fetch needs a ref and a property"));
					yield actions.fetch(target, parsed).thenApply(value ->
						new Frame.Result(id, true, null, null, RefCodec.valueToJson(value)));
				}
				case "gateway-ping" -> actions.gatewayPing().thenApply(ping ->
					new Frame.Result(id, true, null, null, new com.google.gson.JsonPrimitive(ping)));
				default -> CompletableFuture.completedFuture(invalid(id, "The bot has no action '" + frame.kind() + "'"));
			};
			return stage;
		} catch (DiscordException | IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private static Frame.Result message(String id, MessageRef message) {
		return new Frame.Result(id, true, Long.toString(message.id()), null);
	}

	private static @Nullable String text(JsonObject args, String key) {
		JsonElement value = args.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	@SuppressWarnings("unchecked")
	private static <T extends DiscordRef> @Nullable T ref(JsonObject args, String key, Class<T> type) {
		DiscordRef ref = RefCodec.fromJson(args.get(key));
		return type.isInstance(ref) ? (T) ref : null;
	}
}
