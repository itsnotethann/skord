package io.github.itsnotethann.skord.bridge.api;

import java.util.concurrent.CompletionStage;

import org.jetbrains.annotations.Nullable;

/** Runs when someone uses one of the server's Discord commands. Called on the platform's main thread. */
@FunctionalInterface
public interface CommandHandler {

	/**
	 * Handles one use of the command. Return a stage if the answer will come later: while it is incomplete the
	 * bridge tells the bot the handler has paused, so the bot shows "thinking..." instead of treating the silence
	 * as a failure. Return null when the handler has finished (replying or not).
	 */
	@Nullable CompletionStage<?> handle(CommandContext context);
}
