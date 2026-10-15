package io.github.itsnotethann.skord.discord;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

public record DiscordEvent(EventSpec.Kind kind, Map<String, Object> data, @Nullable String id, boolean redelivered,
						   EventCompletion completion) {

	public DiscordEvent(EventSpec.Kind kind, Map<String, Object> data) {
		this(kind, data, null, false, new EventCompletion());
	}
}
