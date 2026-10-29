package io.github.itsnotethann.skord.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.util.LiteralUtils;
import ch.njol.util.Kleenean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.rest.KeyedResult;
import io.github.itsnotethann.skord.rest.RestEffect;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

public final class SharedSyntax {

	private static final String NAME = "[\\p{IsAlphabetic}_][\\p{IsAlphabetic}\\p{IsDigit}_]*";
	private static final String QUOTED = "\"(?:[^\"]|\"\")*\"";
	private static final String ARGUMENTS = "(?:" + QUOTED + "|\\((?:[^()\"]|" + QUOTED + ")*\\)|[^()\"])*";

	private static final Map<PlatformEvent, List<String>> FAILED = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<PlatformEvent, DiscordError> FIRST_FAILURE = Collections.synchronizedMap(new WeakHashMap<>());

	private SharedSyntax() {}

	public static void register() {
		StructSharedFunction.register();
		Skript.registerEffect(EffCall.class,
			RestEffect.rest("<" + NAME + "\\(" + ARGUMENTS + "\\)> from (all:all servers|%-string%)"));
		Skript.registerExpression(ExprFailedServers.class, String.class, ExpressionType.SIMPLE,
			"[the] servers (that failed|not answering) in the last call");
	}

	public static final class EffCall extends RestEffect {

		private String function;
		private final List<Expression<?>> arguments = new ArrayList<>();
		private @Nullable Expression<String> target;
		private boolean all;

		@Override
		@SuppressWarnings("unchecked")
		protected boolean initRest(Expression<?>[] exprs, int matchedPattern, ParseResult parseResult) {
			String call = parseResult.regexes.getFirst().group();
			int open = call.indexOf('(');
			function = call.substring(0, open);
			all = parseResult.hasTag("all");
			target = all ? null : (Expression<String>) exprs[0];
			for (String text : split(call.substring(open + 1, call.length() - 1))) {
				Expression<?> parsed = new SkriptParser(text, SkriptParser.ALL_FLAGS, ParseContext.DEFAULT)
					.parseExpression(Object.class);
				if (parsed == null) {
					Skript.error("Can't understand this argument: " + text);
					return false;
				}
				parsed = LiteralUtils.defendExpression(parsed);
				if (!LiteralUtils.canInitSafely(parsed)) {
					Skript.error("Can't understand this argument: " + text);
					return false;
				}
				arguments.add(parsed);
			}
			SharedCalls calls = SharedCalls.current();
			if (calls != null && !calls.available()) {
				Skript.error("Calling across processes needs a bridge to a bot or game server, and this skord runs embedded in a server");
				return false;
			}
			if (target instanceof Literal<String> literal && calls != null) {
				String name = literal.getSingle();
				List<Frame.FunctionSpec> known = name == null ? null : calls.functionsOf(name);
				if (known != null) {
					Frame.FunctionSpec found = known.stream().filter(spec -> spec.name().equals(function)).findFirst()
						.orElse(null);
					if (found == null) {
						Skript.error("'" + name + "' has no shared function called '" + function + "'"
							+ (known.isEmpty() ? "" : ". It declares: " + String.join(", ",
								known.stream().map(Frame.FunctionSpec::name).toList())));
						return false;
					}
					if (arguments.size() > found.parameters().size()) {
						Skript.error("'" + function + "' takes " + found.parameters().size() + " argument(s), not "
							+ arguments.size());
						return false;
					}
				}
			}
			return true;
		}

		@Override
		protected @Nullable CompletionStage<?> request(PlatformEvent event) {
			SharedCalls calls = SharedCalls.current();
			if (calls == null)
				return CompletableFuture.failedFuture(new DiscordException(
					new DiscordError(DiscordError.Type.OFFLINE, "The bridge is off", null, 0)));
			String name = null;
			if (!all) {
				name = target.getSingle(event);
				if (name == null)
					return null;
			}
			JsonArray args = new JsonArray();
			for (Expression<?> argument : arguments)
				args.add(SharedValues.toJson(argument.getArray(event), argument.isSingle()));
			if (!waits()) {
				if (all)
					calls.all(function, args);
				else
					calls.send(name, function, args);
				return null;
			}
			FAILED.remove(event);
			FIRST_FAILURE.remove(event);
			if (!all)
				return calls.call(name, function, args).thenApply(SharedValues::toSkript);
			return calls.all(function, args).thenApply(outcomes -> {
				Map<String, JsonElement> answers = new TreeMap<>();
				List<String> failed = new ArrayList<>();
				DiscordError first = null;
				for (Map.Entry<String, SharedCalls.Outcome> entry : new TreeMap<>(outcomes).entrySet()) {
					if (entry.getValue().error() == null) {
						answers.put(entry.getKey(), entry.getValue().data());
					} else {
						failed.add(entry.getKey());
						if (first == null)
							first = entry.getValue().error();
					}
				}
				if (!failed.isEmpty()) {
					FAILED.put(event, failed);
					FIRST_FAILURE.put(event, first);
				}
				return SharedValues.perServer(answers);
			});
		}

		@Override
		protected void succeeded(PlatformEvent event) {
			DiscordError first = FIRST_FAILURE.get(event);
			if (first != null)
				raise(event, first);
		}

		@Override
		protected Class<?> resultType() {
			return Object.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return function + "(...) from " + (all ? "all servers" : target.toString(event, debug));
		}
	}

	public static final class ExprFailedServers extends SimpleExpression<String> {

		@Override
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			List<String> failed = FAILED.get(event);
			return failed == null ? new String[0] : failed.toArray(String[]::new);
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
			return "the servers that failed in the last call";
		}
	}

	static List<String> split(String text) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		boolean quoted = false;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"')
				quoted = !quoted;
			else if (!quoted && (c == '(' || c == '{'))
				depth++;
			else if (!quoted && (c == ')' || c == '}'))
				depth--;
			if (c == ',' && !quoted && depth == 0) {
				out.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (!current.toString().isBlank())
			out.add(current.toString().trim());
		return out;
	}

	static Throwable unwrap(Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null)
			error = error.getCause();
		return error;
	}
}
