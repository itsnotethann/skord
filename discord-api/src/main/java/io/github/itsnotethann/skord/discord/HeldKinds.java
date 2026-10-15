package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class HeldKinds {

	private static final Map<EventSpec.Kind, Function<Map<String, Object>, String>> IDS = new EnumMap<>(EventSpec.Kind.class);

	private HeldKinds() {}

	public static synchronized void register(EventSpec.Kind kind, Function<Map<String, Object>, String> id) {
		IDS.put(kind, id);
	}

	public static synchronized boolean heldable(EventSpec.Kind kind) {
		return IDS.containsKey(kind);
	}

	public static synchronized String id(EventSpec.Kind kind, Map<String, Object> data) {
		Function<Map<String, Object>, String> function = IDS.get(kind);
		return function == null ? null : function.apply(data);
	}

	public static synchronized List<String> names() {
		List<String> names = new ArrayList<>();
		for (EventSpec.Kind kind : IDS.keySet())
			names.add(kind.wire().replace('-', ' '));
		return names;
	}
}
