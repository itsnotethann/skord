package io.github.itsnotethann.skord.syntax;

import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.KeyProviderExpression;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.bridge.BridgeData;
import io.github.itsnotethann.skord.bridge.BridgeFailure;
import io.github.itsnotethann.skord.bridge.BridgeHost;
import io.github.itsnotethann.skord.bridge.BridgeRouter;
import io.github.itsnotethann.skord.event.BridgeMessageEvent;
import io.github.itsnotethann.skord.event.BridgeRequestEvent;
import io.github.itsnotethann.skord.rest.KeyedResult;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * The bridge from the bot's side: messages and requests between scripts and linked game servers.
 * <pre>
 * on bridge message "join" from server "survival":
 *     send "%data% joined" to {log-channel}
 *
 * on bridge request "online players":
 *     answer with "Ada" and "Grace"
 *
 * send bridge message "season reset" with "now" to server "survival"
 * ask server "survival" for "online players" and store it in {_players::*}
 * </pre>
 * Data is plain JSON: a scalar, a list (keyed 1, 2, ...) or an object (keyed by field, nested fields joined with
 * {@code ::}). Storing into a list variable keeps the keys, and a list variable with non-numeric keys is sent as
 * an object.
 */
final class BridgeSyntax {

	private BridgeSyntax() {}

	static void register() {
		Skript.registerEvent("Bridge Message", EvtBridgeMessage.class, BridgeMessageEvent.class,
			"bridge message %string% [from server %-string%]");
		Skript.registerEvent("Bridge Request", EvtBridgeRequest.class, BridgeRequestEvent.class,
			"bridge request %string% [from server %-string%]");
		// Marker class only, so the two event types share one name for 'data' and 'event-server'.
		Skript.registerExpression(ExprData.class, Object.class, ExpressionType.SIMPLE, "[the] [event-]data");
		Skript.registerExpression(ExprEventServer.class, String.class, ExpressionType.SIMPLE, "[the] event-server");
		Skript.registerEffect(EffAnswer.class, "answer [the request] with %objects%");
		Skript.registerEffect(EffSendMessage.class,
			RestEffect.rest("send bridge message %string% [with %-objects%] to server %string%"));
		Skript.registerEffect(EffAsk.class, RestEffect.rest("ask server %string% for %string% [with %-objects%]"));
	}

	static final class Names {

		private Names() {}

		static @Nullable String server(PlatformEvent event) {
			return switch (event) {
				case BridgeMessageEvent message -> message.server();
				case BridgeRequestEvent request -> request.server();
				default -> null;
			};
		}
	}

	/** {@code on bridge message "name" [from server "x"]}. */
	public static final class EvtBridgeMessage extends SkriptEvent {

		private String name;
		private @Nullable String server;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			name = (String) args[0].getSingle();
			server = args[1] == null ? null : (String) args[1].getSingle();
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof BridgeMessageEvent message && message.name().equals(name)
				&& (server == null || server.equals(message.server()));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "bridge message \"" + name + "\"" + (server == null ? "" : " from server \"" + server + "\"");
		}
	}

	/** {@code on bridge request "name" [from server "x"]}. */
	public static final class EvtBridgeRequest extends SkriptEvent {

		private String name;
		private @Nullable String server;

		@Override
		public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
			name = (String) args[0].getSingle();
			server = args[1] == null ? null : (String) args[1].getSingle();
			return true;
		}

		@Override
		public boolean check(PlatformEvent event) {
			return event instanceof BridgeRequestEvent request && request.name().equals(name)
				&& (server == null || server.equals(request.server()));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "bridge request \"" + name + "\"" + (server == null ? "" : " from server \"" + server + "\"");
		}
	}

	/** The data of a bridge message or request. Keeps its keys when stored into a list variable. */
	public static final class ExprData extends SimpleExpression<Object> implements KeyProviderExpression<Object> {

		// The keys of the values returned by the last get(); only read straight after, on the script thread.
		private String[] keys = new String[0];

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(BridgeMessageEvent.class) && !getParser().isCurrentEvent(BridgeRequestEvent.class)) {
				Skript.error("'data' only works in a bridge message or bridge request event");
				return false;
			}
			return true;
		}

		@Override
		protected Object @Nullable [] get(PlatformEvent event) {
			KeyedResult data = switch (event) {
				case BridgeMessageEvent message -> message.data();
				case BridgeRequestEvent request -> request.data();
				default -> null;
			};
			if (data == null)
				return null;
			keys = data.keys();
			return data.values();
		}

		@Override
		public String[] getArrayKeys(PlatformEvent event) {
			return keys;
		}

		@Override
		public boolean isSingle() {
			return false;
		}

		@Override
		public Class<?> getReturnType() {
			return Object.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "the data";
		}
	}

	public static final class ExprEventServer extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(BridgeMessageEvent.class) && !getParser().isCurrentEvent(BridgeRequestEvent.class)) {
				Skript.error("'event-server' only works in a bridge message or bridge request event");
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

	/** Reads an expression's values and, when it has them, its keys, as bridge data. */
	static JsonElement dataOf(@Nullable Expression<?> expression, PlatformEvent event) {
		if (expression == null)
			return com.google.gson.JsonNull.INSTANCE;
		Object[] values = expression.getArray(event);
		String[] keys = null;
		if (expression instanceof KeyProviderExpression<?> provider && provider.canReturnKeys()) {
			try {
				keys = provider.getArrayKeys(event);
			} catch (IllegalStateException e) {
				keys = null;
			}
		}
		return BridgeData.fromSkript(values, keys);
	}

	/** {@code answer with "a", "b"} inside {@code on bridge request}. */
	public static final class EffAnswer extends Effect {

		private Expression<?> values;

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			if (!getParser().isCurrentEvent(BridgeRequestEvent.class)) {
				Skript.error("'answer with' only works in a bridge request event");
				return false;
			}
			values = exprs[0];
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			if (event instanceof BridgeRequestEvent request)
				request.answer().complete(dataOf(values, event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "answer with " + values.toString(event, debug);
		}
	}

	public static final class EffSendMessage extends RestEffect {

		private Expression<String> name;
		private @Nullable Expression<?> data;
		private Expression<String> server;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			data = exprs[1];
			server = (Expression<String>) exprs[2];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String message = name.getSingle(event);
			String target = server.getSingle(event);
			if (message == null || target == null)
				return null;
			BridgeRouter router = BridgeRouter.current();
			if (router == null)
				return java.util.concurrent.CompletableFuture.failedFuture(
					new BridgeFailure("offline", "The bridge is off"));
			return router.sendMessage(target, message, dataOf(data, event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "send bridge message " + name.toString(event, debug) + " to server " + server.toString(event, debug);
		}
	}

	public static final class EffAsk extends RestEffect {

		private Expression<String> server;
		private Expression<String> name;
		private @Nullable Expression<?> data;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			server = (Expression<String>) exprs[0];
			name = (Expression<String>) exprs[1];
			data = exprs[2];
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			String target = server.getSingle(event);
			String question = name.getSingle(event);
			if (target == null || question == null)
				return null;
			BridgeRouter router = BridgeRouter.current();
			if (router == null)
				return java.util.concurrent.CompletableFuture.failedFuture(
					new BridgeFailure("offline", "The bridge is off"));
			return router.ask(target, question, dataOf(data, event));
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "ask server " + server.toString(event, debug) + " for " + name.toString(event, debug);
		}
	}
}
