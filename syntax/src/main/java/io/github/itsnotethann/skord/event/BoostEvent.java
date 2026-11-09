package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.UserRef;

public final class BoostEvent extends SkordEvent implements HeldEvent {

	private final MemberRef member;
	private final long startEpoch;
	private final String id;
	private final boolean redelivered;

	public BoostEvent(MemberRef member, long startEpoch, String id, boolean redelivered) {
		this.member = member;
		this.startEpoch = startEpoch;
		this.id = id;
		this.redelivered = redelivered;
	}

	public static String id(long guild, long user, long startEpoch) {
		return "boost:" + guild + ":" + user + ":" + startEpoch;
	}

	public MemberRef member() {
		return member;
	}

	public UserRef user() {
		return member.user();
	}

	public GuildRef guild() {
		return member.guildRef();
	}

	public long startEpoch() {
		return startEpoch;
	}

	@Override
	public String eventId() {
		return id;
	}

	@Override
	public boolean redelivered() {
		return redelivered;
	}
}
