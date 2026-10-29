package io.github.itsnotethann.skord.syntax;

import java.time.Instant;

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
import io.github.itsnotethann.skord.bridge.BridgeHost;
import io.github.itsnotethann.skord.bridge.BridgeRouter;
import io.github.itsnotethann.skord.event.ServerConnectEvent;
import io.github.itsnotethann.skord.event.ServerDisconnectEvent;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * The bridge from the bot's side: which linked game servers are connected, and when they came and went.
 * <pre>
 * on server connect:
 *     send "%event-server% is up" to {log-channel}
 *
 * if server "survival" is online:
 *     reply with "survival is up"
 * </pre>
 * Calling across processes is {@code shared function} and {@code name() from "server"}, in the shared syntax.
 */
public final class BridgeSyntax {

	private BridgeSyntax() {}

	public static void register() {
		Skript.registerExpression(ExprEventServer.class, String.class, ExpressionType.SIMPLE, "[the] event-server");
		Skript.registerEvent("Server Connect", EvtServerConnect.class, ServerConnectEvent.class, "server connect[ed]");
		Skript.registerEvent("Server Disconnect", EvtServerDisconnect.class, ServerDisconnectEvent.class,
			"server disconnect[ed]");
		Skript.registerExpression(ExprEventTime.class, Date.class, ExpressionType.SIMPLE, "[the] event-time");
		Skript.registerCondition(CondServerOnline.class, "server %string% is online",
			"server %string% (isn't|is not) online");
		Skript.registerExpression(ExprLinkedServers.class, String.class, ExpressionType.SIMPLE, "all linked servers");
		Skript.registerExpression(ExprOnlineServers.class, String.class, ExpressionType.SIMPLE, "online servers");
		Skript.registerExpression(ExprLastSeen.class, Date.class, ExpressionType.COMBINED,
			"[the] last seen [time] of server %string%");
	}

	static final class Names {

		private Names() {}

		static @Nullable String server(PlatformEvent event) {
			return switch (event) {
				case ServerConnectEvent connect -> connect.server();
				case ServerDisconnectEvent disconnect -> disconnect.server();
				default -> null;
			};
		}
	}

	public static final class ExprEventServer extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(ServerConnectEvent.class)
				&& !getParser().isCurrentEvent(ServerDisconnectEvent.class)) {
				Skript.error("'event-server' only works in a server connect or server disconnect event");
				return false;
			}
			return true;
		}
		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			String server = Names.server(event);
			return server == null ? null : new String[] {server};
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
			return "the event-server";
		}
	}

	public static final class EvtServerConnect extends SkriptEvent {

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof ServerConnectEvent;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "server connect";
		}
	}

	public static final class EvtServerDisconnect extends SkriptEvent {

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof ServerDisconnectEvent;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "server disconnect";
		}
	}

	public static final class ExprEventTime extends SimpleExpression<Date> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(ServerConnectEvent.class)
				&& !getParser().isCurrentEvent(ServerDisconnectEvent.class)) {
				Skript.error("'event-time' only works in a server connect or server disconnect event");
				return false;
			}
			return true;
		}

		@Override
		protected Date @Nullable [] get(PlatformEvent event) {
			Instant time = switch (event) {
				case ServerConnectEvent connect -> connect.time();
				case ServerDisconnectEvent disconnect -> disconnect.time();
				default -> null;
			};
			return time == null ? null : new Date[] {new Date(time.toEpochMilli())};
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
			return "the event-time";
		}
	}

	public static final class CondServerOnline extends Condition {

		private Expression<String> server;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			setNegated(matchedPattern == 1);
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			String name = server.getSingle(event);
			BridgeRouter router = BridgeRouter.current();
			boolean online = name != null && router != null && router.isOnline(name);
			return online != isNegated();
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "server " + server.toString(event, debug) + (isNegated() ? " is not online" : " is online");
		}
	}

	public static final class ExprLinkedServers extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			BridgeHost host = BridgeHost.installed();
			return host == null ? new String[0]
				: host.links().list().stream().map(link -> link.name()).toArray(String[]::new);
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
			return "all linked servers";
		}
	}

	public static final class ExprOnlineServers extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String[] get(PlatformEvent event) {
			BridgeRouter router = BridgeRouter.current();
			return router == null ? new String[0] : router.onlineServers().toArray(String[]::new);
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
			return "online servers";
		}
	}

	public static final class ExprLastSeen extends SimpleExpression<Date> {

		private Expression<String> server;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			return true;
		}

		@Override
		protected Date @Nullable [] get(PlatformEvent event) {
			String name = server.getSingle(event);
			BridgeRouter router = BridgeRouter.current();
			if (name == null || router == null)
				return null;
			Instant seen = router.lastSeen(name);
			return seen == null ? null : new Date[] {new Date(seen.toEpochMilli())};
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
			return "the last seen time of server " + server.toString(event, debug);
		}
	}
}
