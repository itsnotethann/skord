package io.github.itsnotethann.skord.testbot;

import java.util.ArrayList;
import java.util.List;

import io.github.itsnotethann.skord.protocol.DiscordPermission;
import net.dv8tion.jda.api.Permission;

/**
 * The protocol mirrors JDA's permissions by name so it needs no JDA. This fails the run when a JDA upgrade adds,
 * renames or relabels one, so a script's {@code permission:} never silently stops matching.
 */
final class ProtocolDrift {

	private ProtocolDrift() {}

	/** The problems found, one per line; empty when the two agree. */
	static List<String> check() {
		List<String> problems = new ArrayList<>();
		for (Permission jda : Permission.values()) {
			if (jda == Permission.UNKNOWN)
				continue;
			DiscordPermission mirror;
			try {
				mirror = DiscordPermission.valueOf(jda.name());
			} catch (IllegalArgumentException e) {
				problems.add("protocol lacks DiscordPermission." + jda.name() + " (\"" + jda.getName() + "\")");
				continue;
			}
			if (!mirror.label.equals(jda.getName()))
				problems.add("DiscordPermission." + jda.name() + " is labelled \"" + mirror.label
					+ "\" but JDA calls it \"" + jda.getName() + "\"");
		}
		for (DiscordPermission mirror : DiscordPermission.values()) {
			try {
				Permission.valueOf(mirror.name());
			} catch (IllegalArgumentException e) {
				problems.add("DiscordPermission." + mirror.name() + " no longer exists in JDA");
			}
		}
		return problems;
	}
}
