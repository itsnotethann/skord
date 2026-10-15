package io.github.itsnotethann.skord.bridge.api;

import com.google.gson.JsonElement;

/** Receives a named message from the bot. Called on the platform's main thread. */
@FunctionalInterface
public interface MessageHandler {

	void handle(JsonElement data);
}
