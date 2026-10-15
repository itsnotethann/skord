package io.github.itsnotethann.skord.bridge.api;

import java.nio.file.Path;
import java.util.logging.Level;

/**
 * What the bridge needs from the server it runs on. Paper and Minestom each implement it in a thin jar; a
 * framework with its own platform layer implements it itself.
 */
public interface BridgePlatform {

	/** Short name sent in {@code hello}, such as {@code paper} or {@code minestom}. */
	String name();

	/** A folder the bridge may keep its own files in: the inbox and outbox of durable delivery live here. */
	Path dataDirectory();

	/** Runs on the server's main thread. Every handler and listener the bridge calls goes through this. */
	void runOnMain(Runnable task);

	void log(Level level, String message);
}
