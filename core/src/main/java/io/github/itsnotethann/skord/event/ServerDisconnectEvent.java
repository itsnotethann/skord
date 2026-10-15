package io.github.itsnotethann.skord.event;

import java.time.Instant;

public final class ServerDisconnectEvent extends SkordEvent {

	private final String server;
	private final Instant time;

	public ServerDisconnectEvent(String server, Instant time) {
		this.server = server;
		this.time = time;
	}

	public String server() {
		return server;
	}

	public Instant time() {
		return time;
	}
}
