package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeoutException;

import io.github.itsnotethann.skord.protocol.DiscordPermission;
import org.jetbrains.annotations.Nullable;

/**
 * Why a request failed, as a value a script can branch on. Shown as text, it is the message, so
 * {@code "%last discord error%"} reads as it always did.
 *
 * @param permission the permission that was missing, when it is known. JDA checks most permissions itself before
 *                   sending and then names the one it found missing; Discord's own refusal (code 50013) never does.
 * @param code       Discord's error code, or 0 when the request never reached Discord
 */
public record DiscordError(Type type, String message, @Nullable DiscordPermission permission, int code,
						   @Nullable String scope) {

	public DiscordError(Type type, String message, @Nullable DiscordPermission permission, int code) {
		this(type, message, permission, code, null);
	}

	/** Shown to scripts in lower case with spaces: {@code missing permission}, {@code unknown channel}. */
	public enum Type {
		MISSING_PERMISSION,
		MISSING_ACCESS,
		/** The bot's highest role is not above the role or member it acted on. */
		HIERARCHY,
		UNKNOWN_CHANNEL,
		UNKNOWN_MESSAGE,
		UNKNOWN_MEMBER,
		UNKNOWN_ROLE,
		UNKNOWN_USER,
		/** Something Discord, or JDA's own checks, rejected as malformed: a name too long, an empty message. */
		INVALID_INPUT,
		TIMED_OUT,
		/** A linked game server isn't connected. */
		OFFLINE,
		/** A linked game server answered with an error, or has no answer for the request. */
		REFUSED,
		/** The target has no shared function of that name. */
		NO_SUCH_FUNCTION,
		/** A shared function raised an error. */
		ERROR,
		OUT_OF_SCOPE,
		OTHER;

		/** The wire and script spelling: lower case, spaces for underscores. */
		public String label() {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		}

		public static @Nullable Type ofLabel(String label) {
			for (Type type : values()) {
				if (type.label().equals(label))
					return type;
			}
			return null;
		}
	}

	private static final List<ErrorMapper> MAPPERS = new ArrayList<>();

	static {
		for (ErrorMapper mapper : ServiceLoader.load(ErrorMapper.class, DiscordError.class.getClassLoader()))
			MAPPERS.add(mapper);
	}

	public static DiscordError of(Throwable error) {
		for (ErrorMapper mapper : MAPPERS) {
			DiscordError mapped = mapper.map(error);
			if (mapped != null)
				return mapped;
		}
		return switch (error) {
			case DiscordException failure -> failure.error();
			case TimeoutException timeout -> new DiscordError(Type.TIMED_OUT, "Discord didn't answer in time", null, 0);
			case IllegalArgumentException invalid -> new DiscordError(Type.INVALID_INPUT, describe(invalid), null, 0);
			default -> new DiscordError(Type.OTHER, describe(error), null, 0);
		};
	}

	public static String describe(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}

	@Override
	public String toString() {
		return message;
	}
}
