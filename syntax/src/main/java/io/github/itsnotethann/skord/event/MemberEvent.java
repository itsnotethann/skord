package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.UserRef;

public abstract sealed class MemberEvent extends SkordEvent permits MemberEvent.Join, MemberEvent.Leave {

	private final MemberRef member;

	MemberEvent(MemberRef member) {
		this.member = member;
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

	public static final class Join extends MemberEvent {

		public Join(MemberRef member) {
			super(member);
		}
	}

	public static final class Leave extends MemberEvent {

		public Leave(MemberRef member) {
			super(member);
		}
	}
}
