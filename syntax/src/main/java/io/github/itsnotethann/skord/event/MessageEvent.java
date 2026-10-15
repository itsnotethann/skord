package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import org.jetbrains.annotations.Nullable;

public final class MessageEvent extends SkordEvent {

	private final MessageRef message;
	private final UserRef author;
	private final ChannelRef channel;
	private final @Nullable GuildRef guild;

	public MessageEvent(MessageRef message, UserRef author, ChannelRef channel, @Nullable GuildRef guild) {
		this.message = message;
		this.author = author;
		this.channel = channel;
		this.guild = guild;
	}

	public MessageRef message() {
		return message;
	}

	public UserRef author() {
		return author;
	}

	public ChannelRef channel() {
		return channel;
	}

	public @Nullable GuildRef guild() {
		return guild;
	}
}
