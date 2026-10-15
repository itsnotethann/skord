package io.github.itsnotethann.skord.syntax;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.parser.ParserInstance;
import org.skriptlang.skript.lang.script.Script;

final class Deprecation {

	private static final Map<Script, Set<String>> WARNED = Collections.synchronizedMap(new WeakHashMap<>());

	private Deprecation() {}

	static void warnOnce(ParserInstance parser, String syntax, String replacement) {
		Script script = parser.getCurrentScript();
		Set<String> seen = WARNED.computeIfAbsent(script, key -> new HashSet<>());
		if (seen.add(syntax))
			Skript.warning("'" + syntax + "' is deprecated: use '" + replacement + "' instead, which works the same "
				+ "on a game server");
	}
}
