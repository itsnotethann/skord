package io.github.itsnotethann.skord.event;

import java.util.List;

import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.Interaction;
import org.jetbrains.annotations.Nullable;

/** Someone picked from a select menu. {@link #values()} are the picked options' values, possibly none. */
public final class SelectEvent extends ComponentEvent {

	private final List<String> values;

	public SelectEvent(String id, List<String> values, Interaction interaction, @Nullable MessageRef message,
					   @Nullable UserRef user, @Nullable MemberRef member, @Nullable ChannelRef channel,
					   @Nullable GuildRef guild) {
		super(id, interaction, message, user, member, channel, guild);
		this.values = List.copyOf(values);
	}

	public List<String> values() {
		return values;
	}
}
