package io.github.itsnotethann.skord.jda;

import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.ErrorMapper;
import io.github.itsnotethann.skord.protocol.DiscordPermission;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import org.jetbrains.annotations.Nullable;

public final class JdaErrorMapper implements ErrorMapper {

	@Override
	public @Nullable DiscordError map(Throwable error) {
		return switch (error) {
			case HierarchyException hierarchy ->
				new DiscordError(DiscordError.Type.HIERARCHY, DiscordError.describe(hierarchy), null, 0);
			case PermissionException missing -> {
				Permission permission = missing.getPermission() == Permission.UNKNOWN ? null : missing.getPermission();
				yield new DiscordError(DiscordError.Type.MISSING_PERMISSION,
					permission == null ? DiscordError.describe(missing) : "Missing permission: " + permission.getName(),
					permission == null ? null : port(permission), 0);
			}
			case ErrorResponseException response -> new DiscordError(switch (response.getErrorResponse()) {
				case MISSING_PERMISSIONS -> DiscordError.Type.MISSING_PERMISSION;
				case MISSING_ACCESS -> DiscordError.Type.MISSING_ACCESS;
				case UNKNOWN_CHANNEL -> DiscordError.Type.UNKNOWN_CHANNEL;
				case UNKNOWN_MESSAGE -> DiscordError.Type.UNKNOWN_MESSAGE;
				case UNKNOWN_MEMBER -> DiscordError.Type.UNKNOWN_MEMBER;
				case UNKNOWN_ROLE -> DiscordError.Type.UNKNOWN_ROLE;
				case UNKNOWN_USER -> DiscordError.Type.UNKNOWN_USER;
				case INVALID_FORM_BODY -> DiscordError.Type.INVALID_INPUT;
				default -> DiscordError.Type.OTHER;
			}, meaning(response), null, response.getErrorCode() > 0
				? response.getErrorCode() : response.getErrorResponse().getCode());
			default -> null;
		};
	}

	private static @Nullable DiscordPermission port(Permission permission) {
		try {
			return DiscordPermission.valueOf(permission.name());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String meaning(ErrorResponseException response) {
		return response.getErrorCode() > 0 ? response.getMeaning() : response.getErrorResponse().getMeaning();
	}
}
