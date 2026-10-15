package io.github.itsnotethann.skord.remote;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.itsnotethann.skord.discord.EventCompletion;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

public final class HandlerTracker {

	private static final Map<PlatformEvent, EventCompletion> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());

	private HandlerTracker() {}

	public static void watch(PlatformEvent event, EventCompletion completion) {
		WATCHED.put(event, completion);
	}

	public static @Nullable EventCompletion of(PlatformEvent event) {
		return WATCHED.get(event);
	}
}
