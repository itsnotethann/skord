package io.github.itsnotethann.skord.event;

import java.util.Map;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.Interaction;
import org.jetbrains.annotations.Nullable;

/**
 * Someone ran a slash command. Holds plain values rather than the gateway event, so testbot can build one
 * without a gateway.
 *
 * @param command the full name, such as {@code ticket close}
 * @param options argument values by name. A user argument holds the {@link MemberRef} when there is one,
 *                otherwise the {@link UserRef}.
 */
public final class SlashEvent extends InteractionEvent {

	private final String command;
	private final Map<String, Object> options;
	private final @Nullable UserRef user;
	private final @Nullable MemberRef member;
	private final @Nullable ChannelRef channel;
	private final @Nullable GuildRef guild;

	public SlashEvent(String command, Interaction interaction, Map<String, Object> options, @Nullable UserRef user,
					  @Nullable MemberRef member, @Nullable ChannelRef channel, @Nullable GuildRef guild) {
		super(interaction);
		this.command = command;
		this.options = options;
		this.user = user;
		this.member = member;
		this.channel = channel;
		this.guild = guild;
	}

	public String command() {
		return command;
	}

	/** Every argument the user gave, by name. */
	public Map<String, Object> options() {
		return java.util.Collections.unmodifiableMap(options);
	}

	public @Nullable Object option(String name) {
		return options.get(name);
	}

	public @Nullable UserRef user() {
		return user;
	}

	public @Nullable MemberRef member() {
		return member;
	}

	public @Nullable ChannelRef channel() {
		return channel;
	}

	public @Nullable GuildRef guild() {
		return guild;
	}
}
