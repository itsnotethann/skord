package io.github.itsnotethann.skord.discord;

import io.github.itsnotethann.skord.protocol.CommandSpec;
import org.jetbrains.annotations.Nullable;

public interface Discord {

	DiscordActions actions();

	DiscordEvents events();

	UserRef self();

	@Nullable ChannelRef cachedChannel(long id);

	@Nullable CategoryRef cachedCategory(long id);

	@Nullable String cachedName(DiscordRef ref);

	@Nullable Boolean cachedBot(UserRef user);

	long cachedGatewayPing();

	@Nullable String checkCommand(CommandSpec spec);

	static void install(Discord discord) {
		DiscordHolder.current = discord;
	}

	static Discord current() {
		Discord discord = DiscordHolder.current;
		if (discord == null)
			throw new IllegalStateException("No Discord implementation is installed");
		return discord;
	}
}
