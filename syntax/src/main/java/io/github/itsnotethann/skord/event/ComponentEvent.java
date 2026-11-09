package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.Interaction;
import org.jetbrains.annotations.Nullable;

/**
 * Someone used a component on a message: a {@link ButtonEvent} or a {@link SelectEvent}. {@code component id},
 * its parts and {@code edit the message to} work in both.
 */
public abstract sealed class ComponentEvent extends InteractionEvent permits ButtonEvent, SelectEvent {

	private final String id;
	private final @Nullable MessageRef message;
	private final @Nullable UserRef user;
	private final @Nullable MemberRef member;
	private final @Nullable ChannelRef channel;
	private final @Nullable GuildRef guild;

	protected ComponentEvent(String id, Interaction interaction, @Nullable MessageRef message, @Nullable UserRef user,
							 @Nullable MemberRef member, @Nullable ChannelRef channel, @Nullable GuildRef guild) {
		super(interaction);
		this.id = id;
		this.message = message;
		this.user = user;
		this.member = member;
		this.channel = channel;
		this.guild = guild;
	}

	public String id() {
		return id;
	}

	public @Nullable MessageRef message() {
		return message;
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
