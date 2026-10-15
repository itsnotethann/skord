package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public record GuildRef(long id, @Nullable String name) implements DiscordRef {

	public GuildRef(long id) {
		this(id, null);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof GuildRef ref && ref.id == id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
