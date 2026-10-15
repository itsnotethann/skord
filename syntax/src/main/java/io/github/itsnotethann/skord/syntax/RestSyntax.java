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
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/** Syntax that makes Discord requests. Each pauses the trigger until Discord answers; see {@link RestEffect}. */
final class RestSyntax {

	private RestSyntax() {}

	static void register() {
		Skript.registerEffect(EffSend.class, RestEffect.rest("send %string% to %channels%"));
		Skript.registerEffect(EffReply.class,
			RestEffect.rest("reply to %discordmessage% with %string%"),
			RestEffect.rest("reply [ephemeral:ephemerally] with %string%"));
		Skript.registerEffect(EffEdit.class, RestEffect.rest("edit %discordmessage% to %string%"));
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

	public static final class EffSend extends RestEffect {

		private Expression<String> text;
		private Expression<ChannelRef> channels;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			text = (Expression<String>) exprs[0];
			channels = (Expression<ChannelRef>) exprs[1];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String value = text.getSingle(event);
			if (value == null || value.isEmpty())
				return null;
			List<CompletionStage<MessageRef>> sends = new ArrayList<>();
			for (ChannelRef channel : channels.getArray(event))
				sends.add(Discord.current().actions().send(channel, value));
			return sends.isEmpty() ? null : RestEffect.all(sends);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "send " + text.toString(event, debug) + " to " + channels.toString(event, debug);
		}
	}

	/**
	 * {@code reply with} answers whatever the event is: the message in a message event, the interaction in a
	 * slash command. In an interaction it goes through the ack state, so it becomes an edit of the "thinking..."
	 * message after a deferral, and a follow-up after the first reply.
	 */
	public static final class EffReply extends RestEffect {

		private @Nullable Expression<MessageRef> message;
		private Expression<String> text;
		private boolean ephemeral;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			if (matchedPattern == 0) {
				message = (Expression<MessageRef>) exprs[0];
				text = (Expression<String>) exprs[1];
				return true;
			}
			ephemeral = parseResult.hasTag("ephemeral");
			text = (Expression<String>) exprs[0];
			if (getParser().isCurrentEvent(SlashEvent.class))
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
			String value = text.getSingle(event);
			if (value == null || value.isEmpty())
				return null;
			if (message != null) {
				MessageRef target = message.getSingle(event);
				return target == null ? null : Discord.current().actions().reply(target, value);
			}
			return switch (event) {
				case InteractionEvent interaction -> interaction.interaction().reply(value, ephemeral, storesResult());
				case MessageEvent received -> Discord.current().actions().reply(received.message(), value);
				default -> null;
			};
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return message == null
				? "reply " + (ephemeral ? "ephemerally " : "") + "with " + text.toString(event, debug)
				: "reply to " + message.toString(event, debug) + " with " + text.toString(event, debug);
		}
	}

	/** Edits through the interaction hook when the message came from one: that's the only way for ephemeral ones. */
	public static final class EffEdit extends RestEffect {

		private Expression<MessageRef> message;
		private Expression<String> text;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			message = (Expression<MessageRef>) exprs[0];
			text = (Expression<String>) exprs[1];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			MessageRef target = message.getSingle(event);
			String value = text.getSingle(event);
			if (target == null || value == null || value.isEmpty())
				return null;
			return Discord.current().actions().edit(target, value);
		}

		@Override
		protected Class<?> resultType() {
			return MessageRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "edit " + message.toString(event, debug) + " to " + text.toString(event, debug);
		}
	}

	public static final class EffDefer extends RestEffect {

		private boolean ephemeral;

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			ephemeral = parseResult.hasTag("ephemeral");
			if (!getParser().isCurrentEvent(SlashEvent.class)) {
				Skript.error("Only an interaction, such as a slash command, can be deferred");
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
