package io.github.itsnotethann.skord.embedded;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.util.Date;
import ch.njol.util.Kleenean;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

public final class EmbeddedSyntax {

	private EmbeddedSyntax() {}

	public static final class BridgeOnlyEvent implements PlatformEvent {
	}

	static boolean refuse(ParseResult parseResult) {
		Skript.error("This needs the bridge, and skord runs embedded in this server, which has no bridge");
		return false;
	}

	public static void register() {
		Skript.registerEvent("Bridge Event", EvtRefused.class, BridgeOnlyEvent.class,
			"server (connect|disconnect)[ed]", "bot (connect|disconnect)[ed]");
		Skript.registerCondition(CondRefused.class, "server %string% is online", "server %string% (isn't|is not) online",
			"[the] bot is online", "[the] bot (isn't|is not) online");
		Skript.registerExpression(ExprRefusedText.class, String.class, ExpressionType.SIMPLE,
			"[the] event-server", "all linked servers", "online servers");
		Skript.registerExpression(ExprRefusedDate.class, Date.class, ExpressionType.COMBINED,
			"[the] last seen [time] of server %string%", "[the] event-time");
	}

	public static final class EvtRefused extends SkriptEvent {

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			return refuse(parseResult);
		}

		@Override
		public boolean check(PlatformEvent event) {
			return false;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "a bridge event";
		}
	}

	public static final class CondRefused extends Condition {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return refuse(parseResult);
		}

		@Override
		public boolean check(PlatformEvent event) {
			return false;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "a bridge condition";
		}
	}

	public static final class ExprRefusedText extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return refuse(parseResult);
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			return null;
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
			return "a bridge value";
		}
	}

	public static final class ExprRefusedDate extends SimpleExpression<Date> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return refuse(parseResult);
		}

		@Override
		protected Date @Nullable [] get(PlatformEvent event) {
			return null;
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Date> getReturnType() {
			return Date.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "a bridge time";
		}
	}
}
