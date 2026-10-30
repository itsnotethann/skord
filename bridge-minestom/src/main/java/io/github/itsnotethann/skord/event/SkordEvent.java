package io.github.itsnotethann.skord.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public abstract class SkordEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}
}
