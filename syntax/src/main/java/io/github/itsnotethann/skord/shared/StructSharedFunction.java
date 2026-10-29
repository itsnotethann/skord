package io.github.itsnotethann.skord.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.function.Function;
import ch.njol.skript.lang.function.FunctionEvent;
import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.Parameter;
import ch.njol.skript.lang.function.Signature;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.protocol.Frame;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.entry.EntryContainer;
import org.skriptlang.skript.lang.event.PlatformEvent;
import org.skriptlang.skript.lang.structure.Structure;

public final class StructSharedFunction extends Structure {

	public static final Priority PRIORITY = new Priority(400);

	private static final Pattern SIGNATURE_PATTERN = Pattern.compile("^shared function ("
		+ Functions.functionNamePattern + ")\\((.*?)\\)(?:\\s*(?:->|::| returns )\\s*(.+))?$");

	static {
		Skript.registerStructure(StructSharedFunction.class, "shared function <.+>");
	}

	public static void register() {}

	private SectionNode source;
	private @Nullable Signature<?> signature;

	@Override
	public boolean init(Literal<?>[] literals, int matchedPattern, ParseResult parseResult, @Nullable EntryContainer entryContainer) {
		assert entryContainer != null;
		SharedCalls calls = SharedCalls.current();
		if (calls != null && !calls.available()) {
			Skript.error("Shared functions need a bridge to a bot or game server, and this skord runs embedded in a server");
			return false;
		}
		this.source = entryContainer.getSource();
		return true;
	}

	@Override
	public boolean preLoad() {
		String raw = source.getKey();
		assert raw != null;
		raw = ScriptLoader.replaceOptions(raw);
		Matcher matcher = SIGNATURE_PATTERN.matcher(raw);
		if (!matcher.matches()) {
			Skript.error("Invalid shared function signature: " + raw);
			return false;
		}
		getParser().setCurrentEvent("function", FunctionEvent.class);
		signature = Functions.parseSignature(getParser().getCurrentScript().getConfig().getFileName(),
			matcher.group(1), matcher.group(2), matcher.group(3), false);
		getParser().deleteCurrentEvent();
		if (signature == null)
			return false;
		for (Parameter<?> parameter : signature.getParameters()) {
			if (!SharedValues.allowed(parameter.getType().getC())) {
				Skript.error("Parameter '" + parameter.name() + "' of shared function '" + signature.getName()
					+ "' is a " + parameter.getType().getName().getSingular() + ", which can't travel between processes. "
					+ "Use text, number, boolean or a Discord object, or a list of them");
				return false;
			}
		}
		if (signature.getReturnType() != null && !SharedValues.allowed(signature.getReturnType().getC())) {
			Skript.error("Shared function '" + signature.getName() + "' returns a "
				+ signature.getReturnType().getName().getSingular() + ", which can't travel between processes. "
				+ "Use text, number, boolean or a Discord object, or a list of them");
			return false;
		}
		return Functions.registerSignature(signature) != null;
	}

	@Override
	public boolean load() {
		getParser().setCurrentEvent("function", FunctionEvent.class);
		assert signature != null;
		Function<?> loaded = Functions.loadFunction(getParser().getCurrentScript(), source, signature);
		getParser().deleteCurrentEvent();
		if (loaded == null)
			return false;
		SharedFunctions.register(spec(signature), arguments -> invoke(signature.getName(), signature, arguments));
		return true;
	}

	@Override
	public boolean postLoad() {
		Functions.validateFunctions();
		return true;
	}

	@Override
	public void unload() {
		if (signature == null)
			return;
		SharedFunctions.unregister(signature.getName());
		Functions.unregisterFunction(signature);
	}

	@Override
	public Priority getPriority() {
		return PRIORITY;
	}

	@Override
	public String toString(@Nullable PlatformEvent event, boolean debug) {
		return "shared function";
	}

	static Frame.FunctionSpec spec(Signature<?> signature) {
		List<Frame.FunctionSpec.Parameter> parameters = new ArrayList<>();
		for (Parameter<?> parameter : signature.getParameters())
			parameters.add(new Frame.FunctionSpec.Parameter(parameter.name(), typeName(parameter.getType()
				.getName().getSingular(), parameter.isSingleValue())));
		String returns = signature.getReturnType() == null ? null
			: typeName(signature.getReturnType().getName().getSingular(), signature.isSingle());
		return new Frame.FunctionSpec(signature.getName(), List.copyOf(parameters), returns);
	}

	private static String typeName(String singular, boolean single) {
		return single ? singular : singular + "s";
	}

	private static java.util.concurrent.CompletionStage<JsonElement> invoke(String name, Signature<?> signature,
																			  JsonElement arguments) {
		Function<?> function = Functions.getGlobalFunction(name);
		if (function == null)
			return CompletableFuture.failedFuture(new IllegalStateException("'" + name + "' is not loaded"));
		Parameter<?>[] parameters = signature.getParameters();
		JsonArray given = arguments != null && arguments.isJsonArray() ? arguments.getAsJsonArray() : new JsonArray();
		if (given.size() > parameters.length || given.size() < signature.getMinParameters())
			return CompletableFuture.failedFuture(new IllegalArgumentException("'" + name + "' takes "
				+ parameters.length + " argument(s), got " + given.size()));
		try {
			Object[][] values = new Object[given.size()][];
			for (int i = 0; i < given.size(); i++)
				values[i] = SharedValues.fromJson(given.get(i), parameters[i].getType().getC(),
					parameters[i].isSingleValue(), "argument '" + parameters[i].name() + "'");
			Object[] returned = function.execute(values);
			return CompletableFuture.completedFuture(SharedValues.result(returned, signature.isSingle()));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		} finally {
			function.resetReturnValue();
		}
	}
}
