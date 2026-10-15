package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public record MessageRef(long id, long channel, @Nullable String content, @Nullable String display) implements DiscordRef {

	public MessageRef(long id, long channel) {
		this(id, channel, null, null);
	}

	@Override
	public @Nullable String name() {
		return display;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof MessageRef ref && ref.id == id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
