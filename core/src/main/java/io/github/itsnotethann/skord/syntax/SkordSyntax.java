package io.github.itsnotethann.skord.syntax;

import ch.njol.skript.Skript;
import ch.njol.skript.conditions.base.PropertyCondition;
import ch.njol.skript.expressions.base.EventValueExpression;
import ch.njol.skript.expressions.base.SimplePropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.EventValues;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.SkordHost;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.ReadyEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * Registers all of skord's syntax. Requests live in {@link RestSyntax}, slash commands in {@link SlashSyntax};
 * this file has the events and the plain, cache-only expressions.
 */
public final class SkordSyntax {

	private SkordSyntax() {}

	public static void register() {
		SkordTypes.register();

		Skript.registerEvent("Ready", SimpleEvent.class, ReadyEvent.class, "[discord] ready");
		Skript.registerEvent("Message Received", SimpleEvent.class, MessageEvent.class,
			"[discord] message [receive[d]]");
		EventValues.registerEventValue(MessageEvent.class, Message.class, MessageEvent::message);
		EventValues.registerEventValue(MessageEvent.class, User.class, MessageEvent::author);
		EventValues.registerEventValue(MessageEvent.class, MessageChannel.class, MessageEvent::channel);
		EventValues.registerEventValue(MessageEvent.class, Guild.class, MessageEvent::guild);

		RestSyntax.register();
		SlashSyntax.register();
		BridgeSyntax.register();

		Skript.registerExpression(ExprSelf.class, User.class, ExpressionType.SIMPLE, "[the] bot [user]");
		Skript.registerExpression(ExprPing.class, Long.class, ExpressionType.SIMPLE, "[the] gateway ping");
		SimplePropertyExpression.register(ExprContent.class, String.class, "content", "discordmessages");
		// 'user's name' rather than 'event-user's name'. A type name alone only fills an omitted argument.
		Skript.registerExpression(ExprUser.class, User.class, ExpressionType.EVENT, "[the] user");
		Skript.registerExpression(ExprMember.class, Member.class, ExpressionType.EVENT, "[the] member");
		Skript.registerExpression(ExprGuild.class, Guild.class, ExpressionType.EVENT, "[the] guild");
		Skript.registerExpression(ExprChannel.class, MessageChannel.class, ExpressionType.EVENT, "[the] channel");
		Skript.registerExpression(ExprChannelById.class, MessageChannel.class, ExpressionType.COMBINED, "channel with id %string%");
		Skript.registerExpression(ExprCategoryById.class, Category.class, ExpressionType.COMBINED, "category with id %string%");
		SimplePropertyExpression.register(ExprName.class, String.class, "[discord] name",
			"discordusers/members/guilds/channels/categories/roles");
		SimplePropertyExpression.register(ExprMention.class, String.class, "mention",
			"discordusers/members/channels/categories/roles");
		SimplePropertyExpression.register(ExprId.class, String.class, "discord id",
			"discordusers/members/guilds/channels/categories/roles/discordmessages");
		PropertyCondition.register(CondBot.class, "[a] bot", "discordusers");
	}

	public static final class ExprSelf extends SimpleExpression<User> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected User[] get(PlatformEvent event) {
			return new User[] {SkordHost.jda().getSelfUser()};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends User> getReturnType() {
			return User.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the bot";
		}
	}

	public static final class ExprPing extends SimpleExpression<Long> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected Long[] get(PlatformEvent event) {
			return new Long[] {SkordHost.jda().getGatewayPing()};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Long> getReturnType() {
			return Long.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the gateway ping";
		}
	}

	public static final class ExprUser extends EventValueExpression<User> {
		public ExprUser() {
			super(User.class);
		}
	}

	public static final class ExprMember extends EventValueExpression<Member> {
		public ExprMember() {
			super(Member.class);
		}
	}

	public static final class ExprGuild extends EventValueExpression<Guild> {
		public ExprGuild() {
			super(Guild.class);
		}
	}

	public static final class ExprChannel extends EventValueExpression<MessageChannel> {
		public ExprChannel() {
			super(MessageChannel.class);
		}
	}

	// Cache lookups: the cache holds every channel of every guild the bot is in, so no request is needed.
	public static final class ExprChannelById extends ById<MessageChannel> {

		@Override
		protected @Nullable MessageChannel find(String id) {
			return SkordHost.jda().getChannelById(MessageChannel.class, id);
		}

		@Override
		public Class<? extends MessageChannel> getReturnType() {
			return MessageChannel.class;
		}
	}

	public static final class ExprCategoryById extends ById<Category> {

		@Override
		protected @Nullable Category find(String id) {
			return SkordHost.jda().getCategoryById(id);
		}

		@Override
		public Class<? extends Category> getReturnType() {
			return Category.class;
		}
	}

	public abstract static class ById<T> extends SimpleExpression<T> {

		private Expression<String> id;

		protected abstract @Nullable T find(String id);

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			id = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected T @Nullable [] get(PlatformEvent event) {
			String value = id.getSingle(event);
			if (value == null || !value.matches("\\d{1,20}"))
				return null;
			T found = find(value);
			if (found == null)
				return null;
			T[] array = (T[]) java.lang.reflect.Array.newInstance(getReturnType(), 1);
			array[0] = found;
			return array;
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return getReturnType().getSimpleName().toLowerCase(java.util.Locale.ROOT) + " with id " + id.toString(event, debug);
		}
	}

	public static final class ExprContent extends SimplePropertyExpression<Message, String> {

		@Override
		public String convert(Message message) {
			return message.getContentRaw();
		}

		@Override
		protected String getPropertyName() {
			return "content";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	public static final class ExprName extends SimplePropertyExpression<Object, String> {

		@Override
		public @Nullable String convert(Object from) {
			return switch (from) {
				case User user -> user.getName();
				case Member member -> member.getEffectiveName();
				case Guild guild -> guild.getName();
				case Channel channel -> channel.getName();
				case Role role -> role.getName();
				default -> null;
			};
		}

		@Override
		protected String getPropertyName() {
			return "name";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	public static final class ExprMention extends SimplePropertyExpression<Object, String> {

		@Override
		public @Nullable String convert(Object from) {
			return from instanceof IMentionable mentionable ? mentionable.getAsMention() : null;
		}

		@Override
		protected String getPropertyName() {
			return "mention";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	// IDs are text: they are identifiers, not quantities, and Skript formats numbers for display.
	public static final class ExprId extends SimplePropertyExpression<Object, String> {

		@Override
		public @Nullable String convert(Object from) {
			return from instanceof ISnowflake snowflake ? snowflake.getId() : null;
		}

		@Override
		protected String getPropertyName() {
			return "discord id";
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}
	}

	public static final class CondBot extends PropertyCondition<User> {

		@Override
		public boolean check(User user) {
			return user.isBot();
		}

		@Override
		protected String getPropertyName() {
			return "a bot";
		}
	}
}
