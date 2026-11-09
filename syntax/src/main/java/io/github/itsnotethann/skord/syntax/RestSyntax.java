package io.github.itsnotethann.skord.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.expressions.base.SimplePropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.MessageComponent;
import io.github.itsnotethann.skord.event.ComponentEvent;
import io.github.itsnotethann.skord.event.InteractionEvent;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.SlashEvent;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.FetchProperty;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.discord.Embed;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/** Syntax that makes Discord requests. Each pauses the trigger until Discord answers; see {@link RestEffect}. */
final class RestSyntax {

	private RestSyntax() {}

	static void register() {
		String o = Outgoing.TAIL, w = Outgoing.SWAPPED, e = Outgoing.EMBEDS_ONLY, m = Outgoing.MENTIONS;
		Skript.registerEffect(EffSend.class,
			RestEffect.rest("send %string% " + o + " " + m + " to %channels%"),
			RestEffect.rest("send %string% " + w + " " + m + " to %channels%"),
			RestEffect.rest("send " + e + " " + m + " to %channels%"));
		Skript.registerEffect(EffReply.class,
			RestEffect.rest("reply to %discordmessage% with %string% " + o + " " + m),
			RestEffect.rest("reply to %discordmessage% with %string% " + w + " " + m),
			RestEffect.rest("reply to %discordmessage% with " + e + " " + m),
			RestEffect.rest("reply [ephemeral:ephemerally] with %string% " + o + " " + m),
			RestEffect.rest("reply [ephemeral:ephemerally] with %string% " + w + " " + m),
			RestEffect.rest("reply [ephemeral:ephemerally] with " + e + " " + m));
		Skript.registerEffect(EffEdit.class,
			RestEffect.rest("edit %discordmessage% to %string%"),
			RestEffect.rest("edit [the] message %discordmessage% to %string% " + o),
			RestEffect.rest("edit [the] message %discordmessage% to %string% " + w),
			RestEffect.rest("edit [the] message %discordmessage% to " + e));
		Skript.registerEffect(EffDeleteMessage.class, RestEffect.rest("delete [the] message %discordmessage%"));
		Skript.registerEffect(EffFetchMessage.class,
			RestEffect.rest("(fetch|retrieve) [the] message with id %string% (in|from) %channel%"));
		Skript.registerExpression(ExprMessagePart.class, Object.class, ExpressionType.PROPERTY,
			"[the] (0:text|1:embeds|2:author|3:channel) of %discordmessage%",
			"%discordmessage%'[s] (0:text|1:embeds|2:author|3:channel)");
		Skript.registerEffect(EffDefer.class, RestEffect.rest("defer [the] reply [ephemeral:ephemerally]"));
		Skript.registerEffect(EffCreateTextChannel.class,
			RestEffect.rest("create [a] text channel (named|called) %string% in [category] %category%"),
			RestEffect.rest("create [a] text channel (named|called) %string% in %guild%"));
		Skript.registerEffect(EffFetch.class,
			RestEffect.rest("fetch [the] (name:name|content:content|roles:roles|avatar:avatar url|bot:bot status) of "
				+ "%discorduser/member/guild/channel/category/role/discordmessage%"),
			RestEffect.rest("fetch [the] gateway ping"));
		Skript.registerExpression(ExprLastError.class, DiscordError.class, ExpressionType.SIMPLE,
			"[the] last (discord|request) error");
		SimplePropertyExpression.register(ExprErrorType.class, DiscordError.Type.class, "error type", "discorderrors");
		SimplePropertyExpression.register(ExprMissingPermission.class, DiscordPermission.class, "missing permission", "discorderrors");
		SimplePropertyExpression.register(ExprMissingScope.class, String.class, "missing scope", "discorderrors");
		SimplePropertyExpression.register(ExprErrorMessage.class, String.class, "error message", "discorderrors");
		SimplePropertyExpression.register(ExprErrorCode.class, Long.class, "error code", "discorderrors");
	}

	/** Which shape of the message part a pattern used: text then embeds, text with components first, or embeds alone. */
	private static Outgoing.Form form(int shape) {
		return switch (shape) {
			case 0 -> Outgoing.Form.TEXT;
			case 1 -> Outgoing.Form.SWAPPED;
			default -> Outgoing.Form.EMBEDS;
		};
	}

	public static final class EffSend extends RestEffect {

		private Outgoing message;
		private Expression<ChannelRef> channels;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			message = Outgoing.parse(exprs, 0, form(matchedPattern), parseResult);
			channels = (Expression<ChannelRef>) exprs[message.next()];
			return message.refuseRemoval("'send'");
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageBody body = message.body(event);
			if (body == null)
				return null;
			String problem = body.problem();
			if (problem != null)
				return Outgoing.refused(problem);
			List<CompletionStage<MessageRef>> sends = new ArrayList<>();
			for (ChannelRef channel : channels.getArray(event))
				sends.add(Discord.current().actions().send(channel, body));
			return sends.isEmpty() ? null : RestEffect.all(sends);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "send " + message.toString(event, debug) + " to " + channels.toString(event, debug);
		}
	}

	/**
	 * {@code reply with} answers whatever the event is: the message in a message event, the interaction in a
	 * slash command. In an interaction it goes through the ack state, so it becomes an edit of the "thinking..."
	 * message after a deferral, and a follow-up after the first reply.
	 */
	public static final class EffReply extends RestEffect {

		private @Nullable Expression<MessageRef> target;
		private Outgoing message;
		private boolean ephemeral;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			if (matchedPattern < 3) {
				target = (Expression<MessageRef>) exprs[0];
				message = Outgoing.parse(exprs, 1, form(matchedPattern), parseResult);
				return message.refuseRemoval("'reply'");
			}
			message = Outgoing.parse(exprs, 0, form(matchedPattern - 3), parseResult);
			if (!message.refuseRemoval("'reply'"))
				return false;
			ephemeral = parseResult.hasTag("ephemeral");
			if (getParser().isCurrentEvent(SlashEvent.class, ComponentEvent.class, io.github.itsnotethann.skord.event.ModalEvent.class))
				return true;
			if (ephemeral) {
				Skript.error("Only an interaction, such as a slash command, can be answered ephemerally");
				return false;
			}
			if (!getParser().isCurrentEvent(MessageEvent.class)) {
				Skript.error("'reply with' on its own only works in a message or slash command event; "
					+ "use 'reply to <message> with'");
				return false;
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageBody body = message.body(event);
			if (body == null)
				return null;
			String problem = body.problem();
			if (problem != null)
				return Outgoing.refused(problem);
			if (target != null) {
				MessageRef to = target.getSingle(event);
				return to == null ? null : Discord.current().actions().reply(to, body);
			}
			return switch (event) {
				case InteractionEvent interaction -> interaction.interaction().reply(body, ephemeral, storesResult());
				case MessageEvent received -> Discord.current().actions().reply(received.message(), body);
				default -> null;
			};
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return target == null
				? "reply " + (ephemeral ? "ephemerally " : "") + "with " + message.toString(event, debug)
				: "reply to " + target.toString(event, debug) + " with " + message.toString(event, debug);
		}
	}

	/**
	 * Edits a message's text, and its embeds and components where the effect names them ({@code with no embeds}
	 * removes them). Through the interaction hook when the message came from one: that's the only way for ephemeral
	 * ones. A message a server put components on keeps sending its clicks to that server.
	 */
	public static final class EffEdit extends RestEffect {

		private Expression<MessageRef> target;
		private Outgoing message;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			target = (Expression<MessageRef>) exprs[0];
			if (matchedPattern == 0) {
				// The form from before embeds: text only, and so no embed or component expressions to read.
				Expression<?>[] padded = {exprs[0], exprs[1], null, null};
				message = Outgoing.parse(padded, 1, Outgoing.Form.TEXT, parseResult);
			} else {
				message = Outgoing.parse(exprs, 1, form(matchedPattern - 1), parseResult);
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageRef to = target.getSingle(event);
			MessageBody body = message.body(event);
			if (to == null || body == null)
				return null;
			String problem = body.problem();
			if (problem != null)
				return Outgoing.refused(problem);
			return Discord.current().actions().edit(to, body);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "edit message " + target.toString(event, debug) + " to " + message.toString(event, debug);
		}
	}

	/** Deletes a message. An unknown message fails into {@code last discord error} with the type unknown message. */
	public static final class EffDeleteMessage extends RestEffect {

		private Expression<MessageRef> target;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			target = (Expression<MessageRef>) exprs[0];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageRef to = target.getSingle(event);
			return to == null ? null : Discord.current().actions().delete(to);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "delete message " + target.toString(event, debug);
		}
	}

	/**
	 * {@code fetch message with id "..." in <channel> and store it in {_msg}}: the message with its text, author and
	 * embeds. Messages can't be stored in variables across restarts, so a script keeps the id and fetches again. An
	 * unknown message leaves the variable unset and puts unknown message in {@code last discord error}.
	 */
	public static final class EffFetchMessage extends RestEffect {

		private Expression<String> id;
		private Expression<ChannelRef> channel;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			channel = (Expression<ChannelRef>) exprs[1];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = id.getSingle(event);
			ChannelRef in = channel.getSingle(event);
			if (value == null || in == null)
				return null;
			long parsed;
			try {
				parsed = Long.parseLong(value.trim());
			} catch (NumberFormatException e) {
				return Outgoing.refused("'" + value + "' isn't a message id");
			}
			return Discord.current().actions().fetchMessage(in, parsed);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fetch the message with id " + id.toString(event, debug) + " in " + channel.toString(event, debug);
		}
	}

	/**
	 * What a message holds: its text, embeds, author and channel. These read what was known when the message was
	 * sent, received or fetched; a message rebuilt from an id knows only its channel, so fetch it first.
	 */
	public static final class ExprMessagePart extends SimpleExpression<Object> {

		private static final String[] NAMES = {"text", "embeds", "author", "channel"};

		private Expression<MessageRef> message;
		private int part;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			message = (Expression<MessageRef>) exprs[0];
			part = parseResult.mark;
			return true;
		}

		@Override
		protected Object @Nullable [] get(PlatformEvent event) {
			MessageRef value = message.getSingle(event);
			if (value == null)
				return null;
			return switch (part) {
				case 0 -> value.content() == null ? null : new String[] {value.content()};
				case 1 -> value.embeds() == null ? null : value.embeds().toArray(Embed[]::new);
				case 2 -> value.author() == null ? null : new UserRef[] {value.author()};
				default -> {
					ChannelRef cached = Discord.current().cachedChannel(value.channel());
					yield new ChannelRef[] {cached != null ? cached : new ChannelRef(value.channel())};
				}
			};
		}

		@Override
		public boolean isSingle() {
			return part != 1;
		}

		@Override
		public Class<?> getReturnType() {
			return switch (part) {
				case 0 -> String.class;
				case 1 -> Embed.class;
				case 2 -> UserRef.class;
				default -> ChannelRef.class;
			};
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return NAMES[part] + " of " + message.toString(event, debug);
		}
	}

	public static final class EffDefer extends RestEffect {

		private boolean ephemeral;

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			ephemeral = parseResult.hasTag("ephemeral");
			if (!getParser().isCurrentEvent(SlashEvent.class, io.github.itsnotethann.skord.event.ModalEvent.class)) {
				Skript.error("Only a slash command or a modal submit can be deferred");
				return false;
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			return event instanceof InteractionEvent interaction ? interaction.interaction().defer(ephemeral) : null;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "defer the reply" + (ephemeral ? " ephemerally" : "");
		}
	}

	public static final class EffCreateTextChannel extends RestEffect {

		private Expression<String> name;
		private Expression<?> parent;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			parent = exprs[1];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = name.getSingle(event);
			Object in = parent.getSingle(event);
			if (value == null || value.isEmpty())
				return null;
			return switch (in) {
				case CategoryRef category -> Discord.current().actions().createTextChannel(category, value);
				case GuildRef guild -> Discord.current().actions().createTextChannel(guild, value);
				case null, default -> null;
			};
		}

		@Override
		protected Class<?> resultType() {
			return ChannelRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "create a text channel named " + name.toString(event, debug) + " in " + parent.toString(event, debug);
		}
	}

	public static final class EffFetch extends RestEffect {

		private @Nullable Expression<?> target;
		private @Nullable FetchProperty property;

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			if (matchedPattern == 1)
				return true;
			target = exprs[0];
			if (parseResult.hasTag("name"))
				property = FetchProperty.NAME;
			else if (parseResult.hasTag("content"))
				property = FetchProperty.CONTENT;
			else if (parseResult.hasTag("roles"))
				property = FetchProperty.ROLES;
			else if (parseResult.hasTag("avatar"))
				property = FetchProperty.AVATAR_URL;
			else
				property = FetchProperty.BOT;
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			if (target == null)
				return Discord.current().actions().gatewayPing();
			if (target.getSingle(event) instanceof DiscordRef ref && property != null)
				return Discord.current().actions().fetch(ref, property);
			return null;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return target == null || property == null ? "fetch the gateway ping"
				: "fetch the " + property.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
					+ " of " + target.toString(event, debug);
		}
	}

	/** Why this trigger's last request failed, or nothing if it succeeded. Reading it marks failures as handled. */
	public static final class ExprLastError extends SimpleExpression<DiscordError> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			RestEffect.handlesErrors(getParser().getCurrentStructure());
			return true;
		}

		@Override
		protected DiscordError @Nullable [] get(PlatformEvent event) {
			DiscordError error = RestEffect.lastError(event);
			return error == null ? null : new DiscordError[] {error};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends DiscordError> getReturnType() {
			return DiscordError.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the last discord error";
		}
	}

	public static final class ExprErrorType extends SimplePropertyExpression<DiscordError, DiscordError.Type> {

		@Override
		public DiscordError.Type convert(DiscordError error) {
			return error.type();
		}

		@Override
		protected String getPropertyName() {
			return "error type";
		}

		@Override
		public Class<? extends DiscordError.Type> getReturnType() {
			return DiscordError.Type.class;
		}
	}

	/** Unset when the permission isn't known, which is always so when Discord itself refused (code 50013). */
	public static final class ExprMissingPermission extends SimplePropertyExpression<DiscordError, DiscordPermission> {

		@Override
		public @Nullable DiscordPermission convert(DiscordError error) {
			return error.permission();
		}

		@Override
		protected String getPropertyName() {
			return "missing permission";
		}

		@Override
		public Class<? extends DiscordPermission> getReturnType() {
			return DiscordPermission.class;
		}
	}

	/** For an out of scope error: the scope word the server's link lacks. Unset for every other error. */
	public static final class ExprMissingScope extends SimplePropertyExpression<DiscordError, String> {

		@Override
		public @Nullable String convert(DiscordError error) {
			return error.scope();
		}

		@Override
		protected String getPropertyName() {
			return "missing scope";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	public static final class ExprErrorMessage extends SimplePropertyExpression<DiscordError, String> {

		@Override
		public String convert(DiscordError error) {
			return error.message();
		}

		@Override
		protected String getPropertyName() {
			return "error message";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	/** Discord's numeric code, for anything the error types don't name. Unset when the request never reached Discord. */
	public static final class ExprErrorCode extends SimplePropertyExpression<DiscordError, Long> {

		@Override
		public @Nullable Long convert(DiscordError error) {
			return error.code() == 0 ? null : (long) error.code();
		}

		@Override
		protected String getPropertyName() {
			return "error code";
		}

		@Override
		public Class<? extends Long> getReturnType() {
			return Long.class;
		}
	}
}
