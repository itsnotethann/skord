package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public sealed interface DiscordRef
	permits GuildRef, ChannelRef, UserRef, MemberRef, RoleRef, CategoryRef, MessageRef {

	long id();

	@Nullable String name();

	default @Nullable String mention() {
		return null;
	}
}
