package io.github.itsnotethann.skord.bridge.api;

import java.util.concurrent.CompletionStage;

import com.google.gson.JsonElement;

@FunctionalInterface
public interface FunctionHandler {

	CompletionStage<JsonElement> handle(JsonElement args);
}
