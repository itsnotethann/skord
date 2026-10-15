package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * {@code sk link create|revoke|list}, as lines of text to print. Kept free of the console so tests can run it.
 */
public final class LinkCommands {

	public static final String USAGE = "Usage: sk link create <server> | sk link revoke <server> | sk link list";

	private LinkCommands() {}

	/**
	 * @param args   what follows {@code link}
	 * @param server the running bridge, or null if it's off; a revoked server is cut off at once
	 * @param onRevoked told the name of each link revoked, to drop what the server left behind
	 */
	public static List<String> run(LinkStore links, @Nullable BridgeServer server, Consumer<String> onRevoked,
								   String[] args) {
		List<String> out = new ArrayList<>();
		String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
		try {
			switch (action) {
				case "create" -> {
					String name = name(args, out);
					if (name == null)
						return out;
					String secret = links.create(name);
					if (secret == null) {
						out.add("A link called '" + name + "' already exists. Revoke it first to get a new secret.");
						return out;
					}
					out.add("Created link '" + name + "'. Put this in that server's bridge.yml; it is shown once "
						+ "and cannot be recovered:");
					out.add("  name:   " + name);
					out.add("  secret: " + secret);
				}
				case "revoke" -> {
					String name = name(args, out);
					if (name == null)
						return out;
					if (!links.revoke(name)) {
						out.add("No link called '" + name + "'.");
						return out;
					}
					if (server != null)
						server.drop(name, io.github.itsnotethann.skord.protocol.Frame.Denied.REVOKED);
					onRevoked.accept(name);
					out.add("Revoked link '" + name + "'.");
				}
				case "list" -> {
					List<LinkStore.Link> all = links.list();
					if (all.isEmpty()) {
						out.add("No links. Create one with: sk link create <server>");
						return out;
					}
					out.add("Links:");
					for (LinkStore.Link link : all) {
						String state = server == null ? "bridge off" : server.isConnected(link.name()) ? "connected" : "offline";
						out.add("  " + link.name() + "  (" + state + ", created " + link.created() + ")");
					}
				}
				default -> out.add(USAGE);
			}
		} catch (IOException e) {
			out.add("Could not save links: " + e.getMessage());
		}
		return out;
	}

	private static @Nullable String name(String[] args, List<String> out) {
		if (args.length < 2) {
			out.add(USAGE);
			return null;
		}
		String name = args[1].toLowerCase(Locale.ROOT);
		if (!LinkStore.validName(name)) {
			out.add("'" + args[1] + "' isn't a valid server name: use 1 to 32 lowercase letters, digits, '-' or '_'.");
			return null;
		}
		return name;
	}
}
