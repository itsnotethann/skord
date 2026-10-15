package io.github.itsnotethann.skord.discord;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class EventCompletion {

	private final CompletableFuture<Void> done = new CompletableFuture<>();
	private int outstanding = 1;

	public synchronized void begin() {
		outstanding++;
	}

	public void end() {
		boolean finished;
		synchronized (this) {
			finished = --outstanding == 0;
		}
		if (finished)
			done.complete(null);
	}

	public CompletionStage<Void> stage() {
		return done;
	}
}
