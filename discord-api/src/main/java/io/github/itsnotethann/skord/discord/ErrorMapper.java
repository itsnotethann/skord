package io.github.itsnotethann.skord.discord;

import org.jetbrains.annotations.Nullable;

public interface ErrorMapper {

	@Nullable DiscordError map(Throwable error);
}
