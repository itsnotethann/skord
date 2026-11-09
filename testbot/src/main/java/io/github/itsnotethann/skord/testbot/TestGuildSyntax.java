package io.github.itsnotethann.skord.testbot;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.event.BoostEvent;
import io.github.itsnotethann.skord.event.MemberEvent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

final class TestGuildSyntax {

	private TestGuildSyntax() {}

	static void register() {
		Skript.registerEffect(EffFireMember.class,
			"fire member join of user %string% in guild %string%",
			"fire member leave of user %string% in guild %string%",
			"fire boost of user %string% in guild %string% at %number%");
		Skript.registerExpression(ExprModeration.class, String.class, ExpressionType.SIMPLE, "[the] fake moderation");
	}

	public static final class EffFireMember extends Effect {

		private Expression<String> user;
		private Expression<String> guild;
		private @Nullable Expression<Number> start;
		private int kind;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			user = (Expression<String>) exprs[0];
			guild = (Expression<String>) exprs[1];
			start = matchedPattern == 2 ? (Expression<Number>) exprs[2] : null;
			kind = matchedPattern;
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			String who = user.getSingle(event);
			String where = guild.getSingle(event);
			if (who == null || where == null)
				return;
			long userId = Long.parseLong(who), guildId = Long.parseLong(where);
			MemberRef member = new MemberRef(userId, guildId, "tester");
			PlatformEvent fired = switch (kind) {
				case 0 -> new MemberEvent.Join(member);
				case 1 -> new MemberEvent.Leave(member);
				default -> {
					Number at = start == null ? null : start.getSingle(event);
					long epoch = at == null ? 0 : at.longValue();
					yield new BoostEvent(new MemberRef(userId, guildId), epoch, BoostEvent.id(guildId, userId, epoch), false);
				}
			};
			Skript.eventBus().fire(fired);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "fire member event";
		}
	}

	public static final class ExprModeration extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			return FakeDiscord.MODERATION.toArray(new String[0]);
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the fake moderation";
		}
	}
}
