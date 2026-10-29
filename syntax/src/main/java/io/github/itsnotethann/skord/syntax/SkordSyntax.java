package io.github.itsnotethann.skord.syntax;

import ch.njol.skript.Skript;
import ch.njol.skript.conditions.base.PropertyCondition;
import ch.njol.skript.expressions.base.EventValueExpression;
import ch.njol.skript.expressions.base.SimplePropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.EventValues;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.discord.HeldKinds;
import io.github.itsnotethann.skord.event.HeldEvent;
import io.github.itsnotethann.skord.event.MessageEvent;
import io.github.itsnotethann.skord.event.ReadyEvent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * Registers all of skord's shared syntax. Requests live in {@link RestSyntax}, slash commands in
 * {@link SlashSyntax}; this file has the events and the plain, cache-only expressions.
 */
public final class SkordSyntax {

	public static final String HELD = "[held:" + (char) 92 + "(held" + (char) 92 + ")]";

	private SkordSyntax() {}

	public static void register() {
		RefTypes.register();

		Skript.registerEvent("Ready", EvtReady.class, ReadyEvent.class, "[discord] ready " + HELD);
		Skript.registerEvent("Message Received", EvtMessage.class, MessageEvent.class,
			"[discord] message [receive[d]] [in channel %-string%] [in guild %-string%] " + HELD);
		EventValues.registerEventValue(MessageEvent.class, MessageRef.class, MessageEvent::message);
		EventValues.registerEventValue(MessageEvent.class, UserRef.class, MessageEvent::author);
		EventValues.registerEventValue(MessageEvent.class, ChannelRef.class, MessageEvent::channel);
		EventValues.registerEventValue(MessageEvent.class, GuildRef.class, MessageEvent::guild);

		Skript.registerExpression(ExprEventId.class, String.class, ExpressionType.SIMPLE, "[the] event id");
		Skript.registerCondition(CondRedelivered.class, "[the] event (is|was) redelivered",
			"[the] event (isn't|is not|wasn't|was not) redelivered");

		RestSyntax.register();
		SlashSyntax.register();
		io.github.itsnotethann.skord.shared.SharedSyntax.register();

		Skript.registerExpression(ExprSelf.class, UserRef.class, ExpressionType.SIMPLE, "[the] bot [user]");
		Skript.registerExpression(ExprPing.class, Long.class, ExpressionType.SIMPLE, "[the] gateway ping");
		SimplePropertyExpression.register(ExprContent.class, String.class, "content", "discordmessages");
		// 'user's name' rather than 'event-user's name'. A type name alone only fills an omitted argument.
		Skript.registerExpression(ExprUser.class, UserRef.class, ExpressionType.EVENT, "[the] user");
		Skript.registerExpression(ExprMember.class, MemberRef.class, ExpressionType.EVENT, "[the] member");
		Skript.registerExpression(ExprGuild.class, GuildRef.class, ExpressionType.EVENT, "[the] guild");
		Skript.registerExpression(ExprChannel.class, ChannelRef.class, ExpressionType.EVENT, "[the] channel");
		Skript.registerExpression(ExprChannelById.class, ChannelRef.class, ExpressionType.COMBINED, "channel with id %string%");
		Skript.registerExpression(ExprCategoryById.class, CategoryRef.class, ExpressionType.COMBINED, "category with id %string%");
		SimplePropertyExpression.register(ExprName.class, String.class, "[discord] name",
			"discordusers/members/guilds/channels/categories/roles");
		SimplePropertyExpression.register(ExprMention.class, String.class, "mention",
			"discordusers/members/channels/categories/roles");
		SimplePropertyExpression.register(ExprId.class, String.class, "discord id",
			"discordusers/members/guilds/channels/categories/roles/discordmessages");
		SimplePropertyExpression.register(ExprMemberGuild.class, GuildRef.class, "guild", "members");
		PropertyCondition.register(CondBot.class, "[a] bot", "discordusers");
	}

	public static boolean allowHeld(ParseResult parseResult, EventSpec.Kind kind) {
		if (!parseResult.hasTag("held") || HeldKinds.heldable(kind))
			return true;
		Skript.error("'(held)' isn't allowed on '" + kind.wire().replace('-', ' ') + "' events: they have no stable id to "
			+ "deduplicate on. Events that can be held: "
			+ (HeldKinds.names().isEmpty() ? "none yet" : String.join(", ", HeldKinds.names())));
		return false;
	}

	public static final class ExprEventId extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			return event instanceof HeldEvent held ? new String[] {held.eventId()} : null;
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the event id";
		}
	}

	public static final class CondRedelivered extends ch.njol.skript.lang.Condition {

		private boolean negated;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			negated = matchedPattern == 1;
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return (event instanceof HeldEvent held && held.redelivered()) != negated;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the event " + (negated ? "isn't" : "is") + " redelivered";
		}
	}

	public static final class EvtReady extends SimpleEvent implements SpecEvent {

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			return allowHeld(parseResult, EventSpec.Kind.READY);
		}

		@Override
		public EventSpec spec() {
			return new EventSpec(EventSpec.Kind.READY);
		}
	}

	public static final class EvtMessage extends SkriptEvent implements SpecEvent {

		private @Nullable String channel;
		private @Nullable String guild;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			channel = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			guild = args[1] == null ? null : ((String) args[1].getSingle()).trim();
			return allowHeld(parseResult, EventSpec.Kind.MESSAGE);
		}

		@Override
		public boolean check(PlatformEvent event) {
			if (!(event instanceof MessageEvent message))
				return false;
			if (channel != null && !channel.equals(Long.toString(message.channel().id())))
				return false;
			return guild == null || message.guild() != null && guild.equals(Long.toString(message.guild().id()));
		}

		@Override
		public EventSpec spec() {
			java.util.Map<String, String> filters = new java.util.LinkedHashMap<>();
			if (channel != null)
				filters.put("channel", channel);
			if (guild != null)
				filters.put("guild", guild);
			return new EventSpec(EventSpec.Kind.MESSAGE, filters, false);
		}

		@Override
		public boolean postLoad() {
			EventSpecs.add(spec());
			return super.postLoad();
		}

		@Override
		public void unload() {
			EventSpecs.remove(spec());
			super.unload();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "message received" + (channel == null ? "" : " in channel \"" + channel + "\"")
				+ (guild == null ? "" : " in guild \"" + guild + "\"");
		}
	}

	public static final class ExprSelf extends SimpleExpression<UserRef> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected UserRef[] get(PlatformEvent event) {
			return new UserRef[] {Discord.current().self()};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends UserRef> getReturnType() {
			return UserRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the bot";
		}
	}

	public static final class ExprPing extends SimpleExpression<Long> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			Deprecation.warnOnce(getParser(), "the gateway ping", "fetch the gateway ping and store it in {_ping}");
			return true;
		}

		@Override
		protected Long[] get(PlatformEvent event) {
			return new Long[] {Discord.current().cachedGatewayPing()};
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

	public static final class ExprUser extends EventValueExpression<UserRef> {
		public ExprUser() {
			super(UserRef.class);
		}
	}

	public static final class ExprMember extends EventValueExpression<MemberRef> {
		public ExprMember() {
			super(MemberRef.class);
		}
	}

	public static final class ExprGuild extends EventValueExpression<GuildRef> {
		public ExprGuild() {
			super(GuildRef.class);
		}
	}

	public static final class ExprChannel extends EventValueExpression<ChannelRef> {
		public ExprChannel() {
			super(ChannelRef.class);
		}
	}

	// Cache lookups: the cache holds every channel of every guild the bot is in, so no request is needed.
	public static final class ExprChannelById extends ById<ChannelRef> {

		@Override
		protected @Nullable ChannelRef find(long id) {
			return Discord.current().cachedChannel(id);
		}

		@Override
		public Class<? extends ChannelRef> getReturnType() {
			return ChannelRef.class;
		}
	}

	public static final class ExprCategoryById extends ById<CategoryRef> {

		@Override
		protected @Nullable CategoryRef find(long id) {
			return Discord.current().cachedCategory(id);
		}

		@Override
		public Class<? extends CategoryRef> getReturnType() {
			return CategoryRef.class;
		}
	}

	public abstract static class ById<T> extends SimpleExpression<T> {

		private Expression<String> id;

		protected abstract @Nullable T find(long id);

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
			if (value == null || !value.matches("\\d{1,18}"))
				return null;
			T found = find(Long.parseLong(value));
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
			return getReturnType().getSimpleName().replace("Ref", "").toLowerCase(java.util.Locale.ROOT) + " with id "
				+ id.toString(event, debug);
		}
	}

	public static final class ExprContent extends SimplePropertyExpression<MessageRef, String> {

		@Override
		public @Nullable String convert(MessageRef message) {
			return message.content();
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
			if (!(from instanceof DiscordRef ref))
				return null;
			String name = ref.name();
			return name != null ? name : Discord.current().cachedName(ref);
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
			return from instanceof DiscordRef ref ? ref.mention() : null;
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
			return from instanceof DiscordRef ref ? Long.toString(ref.id()) : null;
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

	public static final class ExprMemberGuild extends SimplePropertyExpression<MemberRef, GuildRef> {

		@Override
		public GuildRef convert(MemberRef member) {
			return member.guildRef();
		}

		@Override
		protected String getPropertyName() {
			return "guild";
		}

		@Override
		public Class<? extends GuildRef> getReturnType() {
			return GuildRef.class;
		}
	}

	public static final class CondBot extends PropertyCondition<UserRef> {

		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			Deprecation.warnOnce(getParser(), "<user> is a bot", "fetch the bot status of <user> and store it in {_bot}");
			return super.init(expressions, matchedPattern, isDelayed, parseResult);
		}

		@Override
		public boolean check(UserRef user) {
			return Boolean.TRUE.equals(Discord.current().cachedBot(user));
		}

		@Override
		protected String getPropertyName() {
			return "a bot";
		}
	}
}
