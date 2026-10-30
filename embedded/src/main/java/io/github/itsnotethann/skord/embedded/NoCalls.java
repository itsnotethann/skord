package io.github.itsnotethann.skord.embedded;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.shared.SharedCalls;
import org.jetbrains.annotations.Nullable;

final class NoCalls implements SharedCalls {

	private static <T> CompletionStage<T> off() {
		return CompletableFuture.failedFuture(new DiscordException(
			new DiscordError(DiscordError.Type.OFFLINE, "The bridge is off in embedded mode", null, 0)));
	}

	@Override
	public CompletionStage<JsonElement> call(String target, String function, JsonArray args) {
		return off();
	}

	@Override
	public void send(String target, String function, JsonArray args) {
	}

	@Override
	public CompletionStage<Map<String, Outcome>> all(String function, JsonArray args) {
		return off();
	}

	@Override
	public @Nullable List<Frame.FunctionSpec> functionsOf(String target) {
		return null;
	}

	@Override
	public boolean available() {
		return false;
	}
}
