package io.github.itsnotethann.skord.compat;

import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.Task;
import org.bukkit.event.Event;

public final class Engine {

	private Engine() {}

	public static void fire(Event event) {
		event.callEvent();
	}

	public static Task sync(Runnable task, long delay, long period) {
		return MinecraftServer.getSchedulerManager().scheduleNextTick(task);
	}
}
