package io.github.itsnotethann.skord.rest;

import java.util.concurrent.TimeoutException;

import io.github.itsnotethann.skord.bridge.BridgeFailure;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import org.jetbrains.annotations.Nullable;

/**
 * Why a request failed, as a value a script can branch on. Shown as text, it is the message, so
 * {@code "%last discord error%"} reads as it always did.
 *
 * @param permission the permission that was missing, when it is known. JDA checks most permissions itself before
 *                   sending and then names the one it found missing; Discord's own refusal (code 50013) never does.
 * @param code       Discord's error code, or 0 when the request never reached Discord
 */
public record DiscordError(Type type, String message, @Nullable Permission permission, int code) {

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
		OTHER
	}

	public static DiscordError of(Throwable error) {
		return switch (error) {
			case HierarchyException hierarchy -> new DiscordError(Type.HIERARCHY, message(hierarchy), null, 0);
			case PermissionException missing -> {
				Permission permission = missing.getPermission() == Permission.UNKNOWN ? null : missing.getPermission();
				yield new DiscordError(Type.MISSING_PERMISSION,
					permission == null ? message(missing) : "Missing permission: " + permission.getName(), permission, 0);
			}
			case ErrorResponseException response -> new DiscordError(switch (response.getErrorResponse()) {
				case MISSING_PERMISSIONS -> Type.MISSING_PERMISSION;
				case MISSING_ACCESS -> Type.MISSING_ACCESS;
				case UNKNOWN_CHANNEL -> Type.UNKNOWN_CHANNEL;
				case UNKNOWN_MESSAGE -> Type.UNKNOWN_MESSAGE;
				case UNKNOWN_MEMBER -> Type.UNKNOWN_MEMBER;
				case UNKNOWN_ROLE -> Type.UNKNOWN_ROLE;
				case UNKNOWN_USER -> Type.UNKNOWN_USER;
				case INVALID_FORM_BODY -> Type.INVALID_INPUT;
				default -> Type.OTHER;
			}, meaning(response), null, response.getErrorCode() > 0
				? response.getErrorCode() : response.getErrorResponse().getCode());
			case TimeoutException timeout -> new DiscordError(Type.TIMED_OUT, "Discord didn't answer in time", null, 0);
			case BridgeFailure failure -> new DiscordError(switch (failure.kind()) {
				case "offline" -> Type.OFFLINE;
				case "timed out" -> Type.TIMED_OUT;
				default -> Type.REFUSED;
			}, message(failure), null, 0);
			case IllegalArgumentException invalid -> new DiscordError(Type.INVALID_INPUT, message(invalid), null, 0);
			default -> new DiscordError(Type.OTHER, message(error), null, 0);
		};
	}

	// A response without an error body (code -1) carries the transport's text, not Discord's; use the known meaning.
	private static String meaning(ErrorResponseException response) {
		return response.getErrorCode() > 0 ? response.getMeaning() : response.getErrorResponse().getMeaning();
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}

	@Override
	public String toString() {
		return message;
	}
}
