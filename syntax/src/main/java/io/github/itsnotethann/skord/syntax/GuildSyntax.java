package io.github.itsnotethann.skord.syntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.expressions.base.SimplePropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.EventValues;
import ch.njol.skript.util.Date;
import ch.njol.skript.util.Timespan;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.event.BoostEvent;
import io.github.itsnotethann.skord.event.MemberEvent;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

final class GuildSyntax {

	private static final long DISCORD_EPOCH = 1420070400000L;
	private static final long MAX_TIMEOUT_SECONDS = 28L * 24 * 60 * 60;
	private static final String REASON = "[(because [of]|with reason|due to) %-string%]";

	private GuildSyntax() {}

	static void register() {
		Skript.registerEvent("Member Join", EvtMember.class, MemberEvent.Join.class,
			"[discord] member join[ed] [in guild %-string%]");
		Skript.registerEvent("Member Leave", EvtMember.class, MemberEvent.Leave.class,
			"[discord] member (leave|left|quit|remove[d]) [in guild %-string%]");
		Skript.registerEvent("Boost", EvtBoost.class, BoostEvent.class,
			"[discord] [server] boost [start[ed]] [in guild %-string%] " + SkordSyntax.HELD);
		EventValues.registerEventValue(MemberEvent.class, MemberRef.class, MemberEvent::member);
		EventValues.registerEventValue(MemberEvent.class, UserRef.class, MemberEvent::user);
		EventValues.registerEventValue(MemberEvent.class, GuildRef.class, MemberEvent::guild);
		EventValues.registerEventValue(BoostEvent.class, MemberRef.class, BoostEvent::member);
		EventValues.registerEventValue(BoostEvent.class, UserRef.class, BoostEvent::user);
		EventValues.registerEventValue(BoostEvent.class, GuildRef.class, BoostEvent::guild);
		EventValues.registerEventValue(BoostEvent.class, Date.class, boost -> new Date(boost.startEpoch() * 1000));

		Skript.registerEffect(EffRole.class,
			RestEffect.rest("(give|add) [the] role %role% to %members%"),
			RestEffect.rest("(take|remove) [the] role %role% from %members%"));
		Skript.registerEffect(EffKick.class, RestEffect.rest("kick [discord] [member] %members% " + REASON));
		Skript.registerEffect(EffBan.class, RestEffect.rest("ban [discord] [member] %members% " + REASON));
		Skript.registerEffect(EffTimeout.class,
			RestEffect.rest("time[ ]out [discord] [member] %members% for %timespan% " + REASON),
			RestEffect.rest("(remove|end) [the] time[ ]out (of|from) [discord] [member] %members% " + REASON));

		Skript.registerExpression(ExprRoleById.class, RoleRef.class, ExpressionType.COMBINED, "role with id %string%");
		Skript.registerExpression(ExprGuildById.class, GuildRef.class, ExpressionType.COMBINED, "guild with id %string%");
		Skript.registerExpression(ExprMemberOf.class, MemberRef.class, ExpressionType.COMBINED,
			"[the] member of %discorduser% in %guild%");
		SimplePropertyExpression.register(ExprCreated.class, Date.class, "creation date",
			"discordusers/members/guilds/channels/categories/roles/discordmessages");
	}

	public static final class EvtMember extends SkriptEvent implements SpecEvent {

		private boolean join;
		private @Nullable String guild;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			join = getEventClasses()[0] == MemberEvent.Join.class;
			guild = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			if (!(event instanceof MemberEvent member) || (event instanceof MemberEvent.Join) != join)
				return false;
			return guild == null || guild.equals(Long.toString(member.guild().id()));
		}

		@Override
		public EventSpec spec() {
			Map<String, String> filters = new LinkedHashMap<>();
			if (guild != null)
				filters.put("guild", guild);
			return new EventSpec(join ? EventSpec.Kind.MEMBER_JOIN : EventSpec.Kind.MEMBER_LEAVE, filters, false);
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
			return "member " + (join ? "join" : "leave") + (guild == null ? "" : " in guild \"" + guild + "\"");
		}
	}

	public static final class EvtBoost extends SkriptEvent implements SpecEvent {

		private @Nullable String guild;
		private boolean held;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			guild = args[0] == null ? null : ((String) args[0].getSingle()).trim();
			held = parseResult.hasTag("held");
			return SkordSyntax.allowHeld(parseResult, EventSpec.Kind.BOOST_START);
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof BoostEvent boost && (guild == null || guild.equals(Long.toString(boost.guild().id())));
		}

		@Override
		public EventSpec spec() {
			return new EventSpec(EventSpec.Kind.BOOST_START, guild == null ? Map.of() : Map.of("guildId", guild), held);
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
			return "boost" + (guild == null ? "" : " in guild \"" + guild + "\"") + (held ? " (held)" : "");
		}
	}

	public static final class EffRole extends RestEffect {

		private Expression<RoleRef> role;
		private Expression<MemberRef> members;
		private boolean add;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			role = (Expression<RoleRef>) exprs[0];
			members = (Expression<MemberRef>) exprs[1];
			add = matchedPattern == 0;
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			RoleRef target = role.getSingle(event);
			if (target == null)
				return null;
			List<CompletionStage<Void>> changes = new ArrayList<>();
			for (MemberRef member : members.getArray(event))
				changes.add(add ? Discord.current().actions().addRole(member, target)
					: Discord.current().actions().removeRole(member, target));
			return changes.isEmpty() ? null : RestEffect.all(changes);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return (add ? "give the role " : "take the role ") + role.toString(event, debug) + (add ? " to " : " from ")
				+ members.toString(event, debug);
		}
	}

	abstract static class MemberAction extends RestEffect {

		Expression<MemberRef> members;
		@Nullable Expression<String> reason;

		@SuppressWarnings("unchecked")
		void bind(Expression<?> members, @Nullable Expression<?> reason) {
			this.members = (Expression<MemberRef>) members;
			this.reason = (Expression<String>) reason;
		}

		abstract CompletionStage<Void> act(MemberRef member, @Nullable String reason, PlatformEvent event);

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String why = reason == null ? null : reason.getSingle(event);
			List<CompletionStage<Void>> done = new ArrayList<>();
			for (MemberRef member : members.getArray(event))
				done.add(act(member, why, event));
			return done.isEmpty() ? null : RestEffect.all(done);
		}

		String because(@Nullable PlatformEvent event, boolean debug) {
			return reason == null ? "" : " because " + reason.toString(event, debug);
		}
	}

	public static final class EffKick extends MemberAction {

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			bind(exprs[0], exprs[1]);
			return true;
		}

		@Override
		CompletionStage<Void> act(MemberRef member, @Nullable String reason, PlatformEvent event) {
			return Discord.current().actions().kick(member, reason);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "kick the member " + members.toString(event, debug) + because(event, debug);
		}
	}

	public static final class EffBan extends MemberAction {

		@Override
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			bind(exprs[0], exprs[1]);
			return true;
		}

		@Override
		CompletionStage<Void> act(MemberRef member, @Nullable String reason, PlatformEvent event) {
			return Discord.current().actions().ban(member, reason);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "ban the member " + members.toString(event, debug) + because(event, debug);
		}
	}

	public static final class EffTimeout extends MemberAction {

		private @Nullable Expression<Timespan> length;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			if (matchedPattern == 0) {
				bind(exprs[0], exprs[2]);
				length = (Expression<Timespan>) exprs[1];
			} else {
				bind(exprs[0], exprs[1]);
			}
			return true;
		}

		@Override
		CompletionStage<Void> act(MemberRef member, @Nullable String reason, PlatformEvent event) {
			long seconds = 0;
			if (length != null) {
				Timespan span = length.getSingle(event);
				if (span == null)
					return Outgoing.refused("A timeout needs a length");
				seconds = Math.max(1, span.getMilliSeconds() / 1000);
				if (seconds > MAX_TIMEOUT_SECONDS)
					return Outgoing.refused("A timeout is at most 28 days");
			}
			return Discord.current().actions().timeout(member, seconds, reason);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return (length == null ? "remove the timeout of the member " + members.toString(event, debug)
				: "time out the member " + members.toString(event, debug) + " for " + length.toString(event, debug))
				+ because(event, debug);
		}
	}

	public static final class ExprRoleById extends SkordSyntax.ById<RoleRef> {

		@Override
		protected @Nullable RoleRef find(long id) {
			return new RoleRef(id, Discord.current().cachedName(new RoleRef(id)));
		}

		@Override
		public Class<? extends RoleRef> getReturnType() {
			return RoleRef.class;
		}
	}

	public static final class ExprGuildById extends SkordSyntax.ById<GuildRef> {

		@Override
		protected @Nullable GuildRef find(long id) {
			return new GuildRef(id, Discord.current().cachedName(new GuildRef(id)));
		}

		@Override
		public Class<? extends GuildRef> getReturnType() {
			return GuildRef.class;
		}
	}

	public static final class ExprMemberOf extends SimpleExpression<MemberRef> {

		private Expression<UserRef> user;
		private Expression<GuildRef> guild;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			user = (Expression<UserRef>) exprs[0];
			guild = (Expression<GuildRef>) exprs[1];
			return true;
		}

		@Override
		protected MemberRef @Nullable [] get(PlatformEvent event) {
			UserRef who = user.getSingle(event);
			GuildRef where = guild.getSingle(event);
			if (who == null || where == null)
				return null;
			return new MemberRef[] {new MemberRef(who.id(), where.id(), who.name())};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends MemberRef> getReturnType() {
			return MemberRef.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the member of " + user.toString(event, debug) + " in " + guild.toString(event, debug);
		}
	}

	public static final class ExprCreated extends SimplePropertyExpression<DiscordRef, Date> {

		@Override
		public @Nullable Date convert(DiscordRef ref) {
			return ref.id() <= 0 ? null : new Date((ref.id() >>> 22) + DISCORD_EPOCH);
		}

		@Override
		protected String getPropertyName() {
			return "creation date";
		}

		@Override
		public Class<? extends Date> getReturnType() {
			return Date.class;
		}
	}
}
