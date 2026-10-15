package io.github.itsnotethann.skord.syntax;

import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import java.util.Locale;
import java.util.function.LongFunction;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.EnumClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.classes.Serializer;
import ch.njol.skript.expressions.base.EventValueExpression;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.registrations.Classes;
import ch.njol.yggdrasil.Fields;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.protocol.ArgKind;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.converter.Converters;

/**
 * Discord types. Variables hold these by ID, never as cached objects: a ref is built from the ID alone, so a
 * variable never carries an object that went stale across a reconnect.
 */
final class RefTypes {

	private RefTypes() {}

	static void register() {
		Classes.registerClass(new ClassInfo<>(UserRef.class, "discorduser")
			.user("(discord )?users?")
			.name("Discord User")
			.description("A Discord account. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(UserRef.class))
			.parser(new NameParser<>("user"))
			.serializer(new IdSerializer<>(UserRef::new)));

		Classes.registerClass(new ClassInfo<>(GuildRef.class, "guild")
			.user("guilds?")
			.name("Guild")
			.description("A Discord server. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(GuildRef.class))
			.parser(new NameParser<>("guild"))
			.serializer(new IdSerializer<>(GuildRef::new)));

		Classes.registerClass(new ClassInfo<>(ChannelRef.class, "channel")
			.user("channels?")
			.name("Channel")
			.description("A channel messages can be sent to: a text channel, thread or DM. Stored in variables by ID.")
			.defaultExpression(new EventValueExpression<>(ChannelRef.class))
			.parser(new NameParser<>("channel"))
			.serializer(new IdSerializer<>(ChannelRef::new)));

		Classes.registerClass(new ClassInfo<>(MemberRef.class, "member")
			.user("members?")
			.name("Member")
			.description("A user in a particular guild, with their roles and nickname. Stored in variables by guild and user ID.")
			.defaultExpression(new EventValueExpression<>(MemberRef.class))
			.parser(new NameParser<>("member"))
			.serializer(new MemberSerializer()));

		Classes.registerClass(new ClassInfo<>(RoleRef.class, "role")
			.user("roles?")
			.name("Role")
			.description("A guild role. Stored in variables by ID.")
			.parser(new NameParser<>("role"))
			.serializer(new IdSerializer<>(RoleRef::new)));

		Classes.registerClass(new ClassInfo<>(CategoryRef.class, "category")
			.user("categor(y|ies)")
			.name("Category")
			.description("A channel category. Stored in variables by ID.")
			.parser(new NameParser<>("category"))
			.serializer(new IdSerializer<>(CategoryRef::new)));

		Converters.registerConverter(MemberRef.class, UserRef.class, MemberRef::user);

		// No serializer: a message can't be resolved from the cache, and fetching every stored message
		// while variables load would cost one request each. Store its ID instead.
		Classes.registerClass(new ClassInfo<>(MessageRef.class, "discordmessage")
			.user("(discord )?messages?")
			.name("Message")
			.description("A Discord message. Not storable in variables; store its ID instead.")
			.defaultExpression(new EventValueExpression<>(MessageRef.class))
			.parser(new NameParser<>("message")));

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
				+ "timed out, offline (a game server isn't connected), refused (it answered with an error), "
				+ "out of scope (its link doesn't allow it) or other."));

		Classes.registerClass(new ClassInfo<>(DiscordPermission.class, "discordpermission")
			.user("(discord )?permissions?")
			.name("Discord Permission")
			.description("A Discord permission, written as Discord shows it: manage channels, send messages, ban members.")
			.parser(new PermissionParser())
			.serializer(new PermissionSerializer()));
	}

	/** The Skript-side Java type of a value of this kind. */
	static Class<?> javaType(ArgKind kind) {
		return switch (kind) {
			case TEXT -> String.class;
			case INTEGER -> Long.class;
			case NUMBER -> Double.class;
			case BOOLEAN -> Boolean.class;
			case USER -> UserRef.class;
			case MEMBER -> MemberRef.class;
			case ROLE -> RoleRef.class;
			case CHANNEL -> ChannelRef.class;
			case CATEGORY -> CategoryRef.class;
		};
	}

	/** Converts an argument value as delivered to the declared type, e.g. a member to its user. */
	static @Nullable Object convert(ArgKind kind, @Nullable Object value) {
		if (value == null || javaType(kind).isInstance(value))
			return value;
		if (kind == ArgKind.USER && value instanceof MemberRef member)
			return member.user();
		return null;
	}

	private static final class NameParser<T extends DiscordRef> extends Parser<T> {

		private final String kind;

		NameParser(String kind) {
			this.kind = kind;
		}

		@Override
		public boolean canParse(ParseContext context) {
			return false;
		}

		@Override
		public String toString(T value, int flags) {
			String name = value.name();
			if (name == null)
				name = Discord.current().cachedName(value);
			return name != null ? name : Long.toString(value.id());
		}

		@Override
		public String toVariableNameString(T value) {
			return kind + ":" + value.id();
		}
	}

	private static final class MemberSerializer extends Serializer<MemberRef> {

		@Override
		public Fields serialize(MemberRef member) {
			Fields fields = new Fields();
			fields.putPrimitive("id", member.id());
			fields.putPrimitive("guild", member.guild());
			return fields;
		}

		@Override
		protected MemberRef deserialize(Fields fields) throws StreamCorruptedException {
			return new MemberRef(fields.getPrimitive("id", Long.class), fields.getPrimitive("guild", Long.class));
		}

		@Override
		public void deserialize(MemberRef value, Fields fields) throws NotSerializableException {
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

	private static final class IdSerializer<T extends DiscordRef> extends Serializer<T> {

		private final LongFunction<T> create;

		IdSerializer(LongFunction<T> create) {
			this.create = create;
		}

		@Override
		public Fields serialize(T value) {
			Fields fields = new Fields();
			fields.putPrimitive("id", value.id());
			return fields;
		}

		@Override
		protected T deserialize(Fields fields) throws StreamCorruptedException {
			return create.apply(fields.getPrimitive("id", Long.class));
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

	private static final class PermissionParser extends Parser<DiscordPermission> {

		@Override
		public @Nullable DiscordPermission parse(String input, ParseContext context) {
			return DiscordPermission.byName(input);
		}

		@Override
		public String toString(DiscordPermission permission, int flags) {
			return permission.label;
		}

		@Override
		public String toVariableNameString(DiscordPermission permission) {
			return "permission:" + permission.name().toLowerCase(Locale.ROOT);
		}
	}

	private static final class PermissionSerializer extends Serializer<DiscordPermission> {

		@Override
		public Fields serialize(DiscordPermission permission) {
			Fields fields = new Fields();
			fields.putObject("name", permission.name());
			return fields;
		}

		@Override
		protected DiscordPermission deserialize(Fields fields) throws StreamCorruptedException {
			try {
				return DiscordPermission.valueOf(fields.getObject("name", String.class));
			} catch (IllegalArgumentException e) {
				throw new StreamCorruptedException("No such permission");
			}
		}

		@Override
		public void deserialize(DiscordPermission permission, Fields fields) throws NotSerializableException {
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
