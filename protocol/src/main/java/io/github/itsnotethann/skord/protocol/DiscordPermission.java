package io.github.itsnotethann.skord.protocol;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * Discord's permissions, by the names a script uses. The constant names mirror the bot library's own, so the bot
 * maps one to the other by name; a check at test time fails if the two drift apart.
 */
public enum DiscordPermission {
	MANAGE_CHANNEL("Manage Channels"),
	MANAGE_SERVER("Manage Server"),
	VIEW_AUDIT_LOGS("View Audit Log"),
	VIEW_CHANNEL("View Channels"),
	VIEW_GUILD_INSIGHTS("View Server Insights"),
	MANAGE_ROLES("Manage Roles"),
	MANAGE_PERMISSIONS("Manage Permissions"),
	MANAGE_WEBHOOKS("Manage Webhooks"),
	MANAGE_GUILD_EXPRESSIONS("Manage Expressions"),
	MANAGE_EVENTS("Manage Events"),
	USE_EMBEDDED_ACTIVITIES("Use Activities"),
	VIEW_CREATOR_MONETIZATION_ANALYTICS("View Creator Analytics"),
	CREATE_GUILD_EXPRESSIONS("Create Expressions"),
	CREATE_SCHEDULED_EVENTS("Create Events"),
	CREATE_INSTANT_INVITE("Create Instant Invite"),
	KICK_MEMBERS("Kick Members"),
	BAN_MEMBERS("Ban Members"),
	NICKNAME_CHANGE("Change Nickname"),
	NICKNAME_MANAGE("Manage Nicknames"),
	MODERATE_MEMBERS("Timeout Members"),
	MESSAGE_ADD_REACTION("Add Reactions"),
	MESSAGE_SEND("Send Messages"),
	MESSAGE_TTS("Send TTS Messages"),
	MESSAGE_MANAGE("Manage Messages"),
	MESSAGE_EMBED_LINKS("Embed Links"),
	MESSAGE_ATTACH_FILES("Attach Files"),
	MESSAGE_HISTORY("Read History"),
	MESSAGE_MENTION_EVERYONE("Mention Everyone"),
	MESSAGE_EXT_EMOJI("Use External Emojis"),
	USE_APPLICATION_COMMANDS("Use Application Commands"),
	MESSAGE_EXT_STICKER("Use External Stickers"),
	MESSAGE_ATTACH_VOICE_MESSAGE("Send Voice Messages"),
	MESSAGE_SEND_POLLS("Create Polls"),
	USE_EXTERNAL_APPLICATIONS("Use External Apps"),
	PIN_MESSAGES("Pin Messages"),
	BYPASS_SLOWMODE("Bypass Slowmode"),
	MANAGE_THREADS("Manage Threads"),
	CREATE_PUBLIC_THREADS("Create Public Threads"),
	CREATE_PRIVATE_THREADS("Create Private Threads"),
	MESSAGE_SEND_IN_THREADS("Send Messages in Threads"),
	PRIORITY_SPEAKER("Priority Speaker"),
	VOICE_STREAM("Video"),
	VOICE_CONNECT("Connect"),
	VOICE_SPEAK("Speak"),
	VOICE_MUTE_OTHERS("Mute Members"),
	VOICE_DEAF_OTHERS("Deafen Members"),
	VOICE_MOVE_OTHERS("Move Members"),
	VOICE_USE_VAD("Use Voice Activity"),
	VOICE_USE_SOUNDBOARD("Use Soundboard"),
	VOICE_USE_EXTERNAL_SOUNDS("Use External Sounds"),
	VOICE_SET_STATUS("Set Voice Channel Status"),
	REQUEST_TO_SPEAK("Request to Speak"),
	ADMINISTRATOR("Administrator");

	public final String label;

	DiscordPermission(String label) {
		this.label = label;
	}

	/** Matches Discord's own label ({@code ban members}) or the constant's name, ignoring case and underscores. */
	public static @Nullable DiscordPermission byName(String name) {
		String wanted = name.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
		for (DiscordPermission permission : values()) {
			if (permission.label.toLowerCase(Locale.ROOT).equals(wanted)
				|| permission.name().replace('_', ' ').toLowerCase(Locale.ROOT).equals(wanted))
				return permission;
		}
		return null;
	}
}
