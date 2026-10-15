package io.github.itsnotethann.skord.bridge.api;

import java.util.concurrent.CompletionStage;

import com.google.gson.JsonElement;

/**
 * Answers a named request from the bot. Called on the platform's main thread; the returned stage may complete
 * later. Completing it exceptionally sends the bot an error instead of data.
 */
@FunctionalInterface
public interface RequestHandler {

	CompletionStage<JsonElement> handle(JsonElement data);
}
