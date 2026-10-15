package io.github.itsnotethann.skord.event;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import io.github.itsnotethann.skord.rest.KeyedResult;

/**
 * A linked server asked the bot something. A script handling it calls {@code answer with ...}, which completes
 * {@link #answer()}; the router sends the response then, even if the handler paused first.
 */
public final class BridgeRequestEvent extends SkordEvent {

	private final String server;
	private final String name;
	private final KeyedResult data;
	private final CompletableFuture<JsonElement> answer = new CompletableFuture<>();

	public BridgeRequestEvent(String server, String name, KeyedResult data) {
		this.server = server;
		this.name = name;
		this.data = data;
	}

	public String server() {
		return server;
	}

	public String name() {
		return name;
	}

	public KeyedResult data() {
		return data;
	}

	public CompletableFuture<JsonElement> answer() {
		return answer;
	}
}
