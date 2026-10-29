package io.github.itsnotethann.skord.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.itsnotethann.skord.bridge.api.FunctionHandler;
import io.github.itsnotethann.skord.protocol.Frame;
import org.jetbrains.annotations.Nullable;

public final class SharedFunctions {

	private static final Map<String, Entry> ENTRIES = new TreeMap<>();

	private SharedFunctions() {}

	public record Entry(Frame.FunctionSpec spec, FunctionHandler handler) {}

	public static synchronized void register(Frame.FunctionSpec spec, FunctionHandler handler) {
		ENTRIES.put(spec.name(), new Entry(spec, handler));
	}

	public static synchronized void unregister(String name) {
		ENTRIES.remove(name);
	}

	public static synchronized @Nullable Entry get(String name) {
		return ENTRIES.get(name);
	}

	public static synchronized List<Frame.FunctionSpec> specs() {
		List<Frame.FunctionSpec> out = new ArrayList<>(ENTRIES.size());
		ENTRIES.values().forEach(entry -> out.add(entry.spec()));
		return out;
	}
}
