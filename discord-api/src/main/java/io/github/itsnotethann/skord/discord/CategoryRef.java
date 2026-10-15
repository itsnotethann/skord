package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public record CategoryRef(long id, @Nullable String name) implements DiscordRef {

	public CategoryRef(long id) {
		this(id, null);
	}

	@Override
	public String mention() {
		return "<#" + id + ">";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CategoryRef ref && ref.id == id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
