package io.github.itsnotethann.skord.protocol;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

public enum Scope {
	SEND, REPLY, EDIT, DELETE, ROLES, CHANNELS, MEMBERS, READ, CALL;

	public final String wire;

	Scope() {
		this.wire = name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable Scope of(String word) {
		String wanted = word.trim().toLowerCase(Locale.ROOT);
		for (Scope scope : values()) {
			if (scope.wire.equals(wanted))
				return scope;
		}
		return null;
	}

	public static String vocabulary() {
		List<String> words = new ArrayList<>();
		for (Scope scope : values())
			words.add(scope.wire);
		return String.join(", ", words);
	}

	public record Parsed(Set<Scope> scopes, List<String> unknown) {}

	public static Parsed parse(String list) {
		Set<Scope> scopes = EnumSet.noneOf(Scope.class);
		List<String> unknown = new ArrayList<>();
		for (String word : list.split(",")) {
			if (word.isBlank())
				continue;
			Scope scope = of(word);
			if (scope == null)
				unknown.add(word.trim());
			else
				scopes.add(scope);
		}
		return new Parsed(scopes, unknown);
	}
}
