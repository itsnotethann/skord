package io.github.itsnotethann.skord.remote;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.bridge.api.Bridge;
import io.github.itsnotethann.skord.bridge.api.BridgePlatform;
import io.github.itsnotethann.skord.bridge.api.BridgeRequestException;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.protocol.Frame;
import io.github.itsnotethann.skord.shared.SharedCalls;
import io.github.itsnotethann.skord.shared.SharedValues;
import org.jetbrains.annotations.Nullable;

public final class ServerCalls implements SharedCalls {

	private final BridgePlatform platform;
	private final Supplier<@Nullable Bridge> bridge;

	ServerCalls(BridgePlatform platform, Supplier<@Nullable Bridge> bridge) {
		this.platform = platform;
		this.bridge = bridge;
	}

	private static <T> CompletionStage<T> failed(DiscordError.Type type, String message) {
		return CompletableFuture.failedFuture(new DiscordException(new DiscordError(type, message, null, 0)));
	}

	public static Throwable translate(Throwable error) {
		while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
			error = error.getCause();
		if (error instanceof BridgeRequestException failure) {
			DiscordError.Type type = DiscordError.Type.ofLabel(failure.errorType());
			return new DiscordException(new DiscordError(type == null ? DiscordError.Type.ERROR : type,
				failure.getMessage(), null, failure.code(), failure.scope()));
		}
		return error;
	}

	@Override
	public CompletionStage<JsonElement> call(String target, String function, JsonArray args) {
		Bridge live = bridge.get();
		if (live == null)
			return failed(DiscordError.Type.OFFLINE, "The bridge isn't running");
		return live.call(target.equals("bot") ? null : target, function, args).handle((answer, error) -> {
			if (error != null)
				throw sneaky(translate(error));
			return answer;
		});
	}

	@Override
	public void send(String target, String function, JsonArray args) {
		Bridge live = bridge.get();
		if (live == null || !live.send(target.equals("bot") ? null : target, function, args))
			platform.log(Level.WARNING, "Call to '" + function + "' on '" + target + "' was not sent: the bot is offline");
	}

	@Override
	public CompletionStage<Map<String, Outcome>> all(String function, JsonArray args) {
		Bridge live = bridge.get();
		if (live == null)
			return failed(DiscordError.Type.OFFLINE, "The bridge isn't running");
		return live.callAll(function, args).handle((answer, error) -> {
			if (error != null)
				throw sneaky(translate(error));
			return SharedValues.outcomes(answer);
		});
	}

	@Override
	public @Nullable List<Frame.FunctionSpec> functionsOf(String target) {
		Bridge live = bridge.get();
		return target.equals("bot") && live != null ? live.botFunctions() : null;
	}

	private static RuntimeException sneaky(Throwable error) {
		return error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
	}
}
