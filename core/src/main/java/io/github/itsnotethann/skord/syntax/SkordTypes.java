package io.github.itsnotethann.skord.syntax;

import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.LongFunction;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.EnumClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.classes.Serializer;
import ch.njol.skript.expressions.base.EventValueExpression;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.registrations.Classes;
import ch.njol.yggdrasil.Fields;
import io.github.itsnotethann.skord.SkordHost;
import io.github.itsnotethann.skord.rest.DiscordError;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.converter.Converters;

/**
 * Discord types. Variables hold these by ID, never as cached JDA objects: an ID is resolved again on load,
 * so a variable never carries an object that went stale across a reconnect.
 */
final class SkordTypes {

	private SkordTypes() {}

	static void register() {
		Classes.registerClass(new ClassInfo<>(User.class, "discorduser")
			.user("(discord )?users?")
			.name("Discord User")
			.description("A Discord account. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(User.class))
			.parser(new NameParser<>(User::getName, "user"))
			.serializer(new IdSerializer<>(SkordTypes::user)));

		Classes.registerClass(new ClassInfo<>(Guild.class, "guild")
			.user("guilds?")
			.name("Guild")
			.description("A Discord server. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(Guild.class))
			.parser(new NameParser<>(Guild::getName, "guild"))
			.serializer(new IdSerializer<>(id -> SkordHost.jda().getGuildById(id))));

		Classes.registerClass(new ClassInfo<>(MessageChannel.class, "channel")
			.user("channels?")
			.name("Channel")
			.description("A channel messages can be sent to: a text channel, thread or DM. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(MessageChannel.class))
			.parser(new NameParser<>(MessageChannel::getName, "channel"))
			.serializer(new IdSerializer<>(id -> SkordHost.jda().getChannelById(MessageChannel.class, id))));

		Classes.registerClass(new ClassInfo<>(Member.class, "member")
			.user("members?")
			.name("Member")
			.description("A user in a particular guild, with their roles and nickname. Stored in variables by guild and user ID.")
			.defaultExpression(new EventValueExpression<>(Member.class))
			.parser(new NameParser<>(Member::getEffectiveName, "member"))
			.serializer(new MemberSerializer()));

		Classes.registerClass(new ClassInfo<>(Role.class, "role")
			.user("roles?")
			.name("Role")
			.description("A guild role. Stored in variables by ID.")
			.parser(new NameParser<>(Role::getName, "role"))
			.serializer(new IdSerializer<>(id -> SkordHost.jda().getRoleById(id))));

		Classes.registerClass(new ClassInfo<>(Category.class, "category")
			.user("categor(y|ies)")
			.name("Category")
			.description("A channel category. Stored in variables by ID.")
			.parser(new NameParser<>(Category::getName, "category"))
			.serializer(new IdSerializer<>(id -> SkordHost.jda().getCategoryById(id))));

		Converters.registerConverter(Member.class, User.class, Member::getUser);

		// No serializer: a message can't be resolved from the cache, and fetching every stored message
		// while variables load would cost one request each. Store its ID instead.
		Classes.registerClass(new ClassInfo<>(Message.class, "discordmessage")
			.user("(discord )?messages?")
			.name("Message")
			.description("A Discord message. Not storable in variables; store its ID instead.")
			.defaultExpression(new EventValueExpression<>(Message.class))
			.parser(new NameParser<>(Message::getContentDisplay, "message")));

		// Not storable: an error describes one request, and is read right after it.
		Classes.registerClass(new ClassInfo<>(DiscordError.class, "discorderror")
			.user("(discord |request )?errors?")
			.name("Discord Error")
			.description("Why a Discord request failed: its error type, the missing permission when known, Discord's "
				+ "error code, and a message. Shown as text, it is the message.")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(DiscordError error, int flags) {
					return error.message();
				}

				@Override
				public String toVariableNameString(DiscordError error) {
					return "discorderror:" + error.type();
				}
			}));

		Classes.registerClass(new EnumClassInfo<>(DiscordError.Type.class, "discorderrortype")
			.user("(discord )?error ?types?")
			.name("Discord Error Type")
			.description("What kind of failure a Discord Error is: missing permission, missing access, hierarchy, "
				+ "unknown channel, unknown message, unknown member, unknown role, unknown user, invalid input, "
				+ "timed out, offline (a game server isn't connected), refused (it answered with an error) or other."));

		Classes.registerClass(new ClassInfo<>(Permission.class, "discordpermission")
			.user("(discord )?permissions?")
			.name("Discord Permission")
			.description("A Discord permission, written as Discord shows it: manage channels, send messages, ban members.")
			.parser(new PermissionParser())
			.serializer(new PermissionSerializer()));
	}

	// Not every user is cached (skord logs in light), so fall back to one REST lookup. This only runs while
	// variables load, on the script thread, never from a JDA callback.
	private static @Nullable User user(long id) {
		User cached = SkordHost.jda().getUserById(id);
		if (cached != null)
			return cached;
		try {
			return SkordHost.jda().retrieveUserById(id).complete();
		} catch (ErrorResponseException e) {
			return null;
		}
	}

	private static final class NameParser<T extends ISnowflake> extends Parser<T> {

		private final Function<T, String> name;
		private final String kind;

		NameParser(Function<T, String> name, String kind) {
			this.name = name;
			this.kind = kind;
		}

		@Override
		public boolean canParse(ParseContext context) {
			return false;
		}

		@Override
		public String toString(T value, int flags) {
			return name.apply(value);
		}

		@Override
		public String toVariableNameString(T value) {
			return kind + ":" + value.getId();
		}
	}

	// A member needs its guild too. Like users, members aren't all cached, so this may make one REST lookup at boot.
	private static final class MemberSerializer extends IdSerializer<Member> {

		MemberSerializer() {
			super(id -> null);
		}

		@Override
		public Fields serialize(Member member) {
			Fields fields = super.serialize(member);
			fields.putPrimitive("guild", member.getGuild().getIdLong());
			return fields;
		}

		@Override
		protected Member deserialize(Fields fields) throws StreamCorruptedException {
			long id = fields.getPrimitive("id", Long.class);
			long guildId = fields.getPrimitive("guild", Long.class);
			Guild guild = SkordHost.jda().getGuildById(guildId);
			if (guild == null)
				throw new StreamCorruptedException("The bot is no longer in guild " + guildId);
			Member cached = guild.getMemberById(id);
			if (cached != null)
				return cached;
			try {
				return guild.retrieveMemberById(id).complete();
			} catch (ErrorResponseException e) {
				throw new StreamCorruptedException("User " + id + " is no longer in guild " + guildId);
			}
		}
	}

	private static class IdSerializer<T extends ISnowflake> extends Serializer<T> {

		private final LongFunction<@Nullable T> resolve;

		IdSerializer(LongFunction<@Nullable T> resolve) {
			this.resolve = resolve;
		}

		@Override
		public Fields serialize(T value) {
			Fields fields = new Fields();
			fields.putPrimitive("id", value.getIdLong());
			return fields;
		}

		@Override
		protected T deserialize(Fields fields) throws StreamCorruptedException {
			long id = fields.getPrimitive("id", Long.class);
			T value = resolve.apply(id);
			if (value == null)
				throw new StreamCorruptedException("Nothing with ID " + id + " exists any more");
			return value;
		}

		@Override
		public void deserialize(T value, Fields fields) throws NotSerializableException {
			throw new NotSerializableException("Discord objects are resolved by ID, not instantiated");
		}

		@Override
		public boolean mustSyncDeserialization() {
			return false;
		}

		@Override
		protected boolean canBeInstantiated() {
			return false;
		}
	}

	// "manage channels" as Discord shows it, or JDA's own name with spaces ("manage channel").
	private static final class PermissionParser extends Parser<Permission> {

		@Override
		public @Nullable Permission parse(String input, ParseContext context) {
			String wanted = input.trim().toLowerCase(Locale.ROOT);
			for (Permission permission : Permission.values()) {
				if (permission == Permission.UNKNOWN)
					continue;
				if (permission.getName().toLowerCase(Locale.ROOT).equals(wanted)
					|| permission.name().toLowerCase(Locale.ROOT).replace('_', ' ').equals(wanted))
					return permission;
			}
			return null;
		}

		@Override
		public String toString(Permission permission, int flags) {
			return permission.getName();
		}

		@Override
		public String toVariableNameString(Permission permission) {
			return "permission:" + permission.name().toLowerCase(Locale.ROOT);
		}
	}

	private static final class PermissionSerializer extends Serializer<Permission> {

		@Override
		public Fields serialize(Permission permission) {
			Fields fields = new Fields();
			fields.putObject("name", permission.name());
			return fields;
		}

		@Override
		protected Permission deserialize(Fields fields) throws StreamCorruptedException {
			try {
				return Permission.valueOf(fields.getObject("name", String.class));
			} catch (IllegalArgumentException e) {
				throw new StreamCorruptedException("No such permission");
			}
		}

		@Override
		public void deserialize(Permission permission, Fields fields) throws NotSerializableException {
			throw new NotSerializableException("Permissions are constants");
		}

		@Override
		public boolean mustSyncDeserialization() {
			return false;
		}

		@Override
		protected boolean canBeInstantiated() {
			return false;
		}
	}
}
