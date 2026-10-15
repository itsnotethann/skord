package io.github.itsnotethann.skord.protocol;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/** Argument types a command can declare. The bot maps each to Discord's option types; servers never need to. */
public enum ArgKind {
	TEXT("text", "string"),
	INTEGER("integer"),
	NUMBER("number"),
	BOOLEAN("boolean"),
	USER("user"),
	MEMBER("member"),
	ROLE("role"),
	CHANNEL("channel"),
	CATEGORY("category");

	private final String[] names;

	ArgKind(String... names) {
		this.names = names;
	}

	/** The name a script writes, and the one that travels on the wire. */
	public String label() {
		return names[0];
	}

	public static @Nullable ArgKind byName(String name) {
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		for (ArgKind kind : values()) {
			for (String candidate : kind.names) {
				if (candidate.equals(wanted))
					return kind;
			}
		}
		return null;
	}

	public static String names() {
		StringBuilder out = new StringBuilder();
		for (ArgKind kind : values())
			out.append(out.isEmpty() ? "" : ", ").append(kind.names[0]);
		return out.toString();
	}
}
