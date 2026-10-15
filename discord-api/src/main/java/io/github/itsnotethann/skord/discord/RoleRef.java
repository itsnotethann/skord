package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public record RoleRef(long id, @Nullable String name) implements DiscordRef {

	public RoleRef(long id) {
		this(id, null);
	}

	@Override
	public String mention() {
		return "<@&" + id + ">";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof RoleRef ref && ref.id == id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
