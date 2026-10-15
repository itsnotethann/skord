package io.github.itsnotethann.skord.syntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.itsnotethann.skord.discord.EventSpec;

public final class EventSpecs {

	private static final Map<EventSpec, Integer> LOADED = new LinkedHashMap<>();

	private EventSpecs() {}

	public static synchronized void add(EventSpec spec) {
		LOADED.merge(spec, 1, Integer::sum);
	}

	public static synchronized void remove(EventSpec spec) {
		LOADED.computeIfPresent(spec, (key, count) -> count > 1 ? count - 1 : null);
	}

	public static synchronized List<EventSpec> current() {
		return new ArrayList<>(LOADED.keySet());
	}
}
