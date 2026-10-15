package io.github.itsnotethann.skord.protocol;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

public enum Listen {
	MESSAGES, MEMBERS, BOOSTS, INVITES, COMPONENTS;

	public final String wire;

	Listen() {
		this.wire = name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable Listen of(String word) {
		String wanted = word.trim().toLowerCase(Locale.ROOT);
		for (Listen listen : values()) {
			if (listen.wire.equals(wanted))
				return listen;
		}
		return null;
	}

	public static String vocabulary() {
		List<String> words = new ArrayList<>();
		for (Listen listen : values())
			words.add(listen.wire);
		return String.join(", ", words);
	}

	public record Parsed(Set<Listen> listens, List<String> unknown) {}

	public static Parsed parse(String list) {
		Set<Listen> listens = EnumSet.noneOf(Listen.class);
		List<String> unknown = new ArrayList<>();
		for (String word : list.split(",")) {
			if (word.isBlank())
				continue;
			Listen listen = of(word);
			if (listen == null)
				unknown.add(word.trim());
			else
				listens.add(listen);
		}
		return new Parsed(listens, unknown);
	}
}
