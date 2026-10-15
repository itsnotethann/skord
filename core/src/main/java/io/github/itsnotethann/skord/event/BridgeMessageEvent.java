package io.github.itsnotethann.skord.event;

import io.github.itsnotethann.skord.rest.KeyedResult;

/**
 * A linked server sent a named message. {@code data} is already in Skript's shape: scalars, and keyed values for
 * lists and objects.
 */
public final class BridgeMessageEvent extends SkordEvent {

	private final String server;
	private final String name;
	private final KeyedResult data;

	public BridgeMessageEvent(String server, String name, KeyedResult data) {
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
}
