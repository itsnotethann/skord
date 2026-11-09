package io.github.itsnotethann.skord.bridge;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

public interface PlayerLinks {

	@Nullable String uuid(String discordId);

	void link(String discordId, String uuid);

	void unlink(String discordId);

	void replaceAll(Map<String, String> links);

	void clearMemory();
}
