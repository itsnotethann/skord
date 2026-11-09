package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.Interaction;
import org.jetbrains.annotations.Nullable;

public final class ButtonEvent extends ComponentEvent {

	public ButtonEvent(String id, Interaction interaction, @Nullable MessageRef message, @Nullable UserRef user,
					   @Nullable MemberRef member, @Nullable ChannelRef channel, @Nullable GuildRef guild) {
		super(id, interaction, message, user, member, channel, guild);
	}
}
