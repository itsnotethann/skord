package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public record MemberRef(long id, long guild, @Nullable String name) implements DiscordRef {

	public MemberRef(long id, long guild) {
		this(id, guild, null);
	}

	public UserRef user() {
		return new UserRef(id, name);
	}

	public GuildRef guildRef() {
		return new GuildRef(guild);
	}

	@Override
	public String mention() {
		return "<@" + id + ">";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof MemberRef ref && ref.id == id && ref.guild == guild;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id) * 31 + Long.hashCode(guild);
	}
}
