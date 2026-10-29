package io.github.itsnotethann.skord.shared;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.protocol.Frame;
import org.jetbrains.annotations.Nullable;

public interface SharedCalls {

	record Outcome(@Nullable JsonElement data, @Nullable DiscordError error) {}

	CompletionStage<JsonElement> call(String target, String function, JsonArray args);

	void send(String target, String function, JsonArray args);

	CompletionStage<Map<String, Outcome>> all(String function, JsonArray args);

	@Nullable List<Frame.FunctionSpec> functionsOf(String target);

	default boolean available() {
		return true;
	}

	final class Holder {
		private static volatile @Nullable SharedCalls installed;

		private Holder() {}
	}

	static void install(@Nullable SharedCalls calls) {
		Holder.installed = calls;
	}

	static @Nullable SharedCalls current() {
		return Holder.installed;
	}
}
