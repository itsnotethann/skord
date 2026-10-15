package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.github.itsnotethann.skord.protocol.Listen;
import io.github.itsnotethann.skord.protocol.Scope;
import org.jetbrains.annotations.Nullable;

/**
 * {@code sk link create|scope|revoke|list}, as lines of text to print. Kept free of the console so tests can run it.
 */
public final class LinkCommands {

	public static final String USAGE = "Usage: sk link create <server> [--allow a,b,c] [--listen a,b] | "
		+ "sk link scope <server> [--allow a,b,c] [--listen a,b] | sk link revoke <server> | sk link list";

	private record Grants(@Nullable Set<Scope> scope, @Nullable Set<Listen> listen) {}

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
					Grants grants = grants(args, false, out);
					if (grants == null)
						return out;
					Set<Scope> scope = grants.scope() == null ? EnumSet.noneOf(Scope.class) : grants.scope();
					Set<Listen> listen = grants.listen() == null ? EnumSet.noneOf(Listen.class) : grants.listen();
					String secret = links.create(name, scope, listen);
					if (secret == null) {
						out.add("A link called '" + name + "' already exists. Revoke it first to get a new secret.");
						return out;
					}
					out.add("Created link '" + name + "'. Put this in that server's bridge.yml; it is shown once "
						+ "and cannot be recovered:");
					out.add("  name:   " + name);
					out.add("  secret: " + secret);
					out.add("  scope:  " + show(scope));
					out.add("  listen: " + showListen(listen));
				}
				case "scope" -> {
					String name = name(args, out);
					if (name == null)
						return out;
					Grants grants = grants(args, true, out);
					if (grants == null)
						return out;
					if (!links.exists(name)) {
						out.add("No link called '" + name + "'.");
						return out;
					}
					if (grants.scope() != null) {
						links.setScope(name, grants.scope());
						out.add("Link '" + name + "' may now: " + show(grants.scope()) + ".");
					}
					if (grants.listen() != null) {
						links.setListen(name, grants.listen());
						out.add("Link '" + name + "' may now listen to: " + showListen(grants.listen()) + ".");
					}
					if (server != null)
						server.pushScope(name);
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
						out.add("  " + link.name() + "  (" + state + ", created " + link.created() + ")  scope: "
							+ show(link.scope()) + "; listen: " + showListen(link.listen()));
					}
				}
				default -> out.add(USAGE);
			}
		} catch (IOException e) {
			out.add("Could not save links: " + e.getMessage());
		}
		return out;
	}

	private static String show(Set<Scope> scope) {
		if (scope.isEmpty())
			return "none (nothing granted; set it with: sk link scope <server> --allow " + Scope.vocabulary() + ")";
		return scope.stream().sorted().map(item -> item.wire).collect(Collectors.joining(", "));
	}

	private static String showListen(Set<Listen> listen) {
		if (listen.isEmpty())
			return "none (no events forwarded; set it with: sk link scope <server> --listen " + Listen.vocabulary() + ")";
		return listen.stream().sorted().map(item -> item.wire).collect(Collectors.joining(", "));
	}

	private static @Nullable Grants grants(String[] args, boolean required, List<String> out) {
		Set<Scope> scope = null;
		Set<Listen> listen = null;
		for (int i = 2; i < args.length; i++) {
			String argument = args[i];
			String flag;
			String list;
			if (argument.equals("--allow") || argument.equals("--listen")) {
				flag = argument;
				if (i + 1 >= args.length) {
					out.add(flag + " needs a list: " + (flag.equals("--allow") ? Scope.vocabulary() : Listen.vocabulary())
						+ ", or none.");
					return null;
				}
				list = args[i + 1];
				i++;
			} else if (argument.startsWith("--allow=") || argument.startsWith("--listen=")) {
				flag = argument.substring(0, argument.indexOf('='));
				list = argument.substring(argument.indexOf('=') + 1);
			} else {
				out.add("Unknown option '" + argument + "'. " + USAGE);
				return null;
			}
			boolean none = list.isBlank() || list.equalsIgnoreCase("none");
			if (flag.equals("--allow")) {
				if (none) {
					scope = EnumSet.noneOf(Scope.class);
					continue;
				}
				Scope.Parsed parsed = Scope.parse(list);
				if (!parsed.unknown().isEmpty()) {
					out.add("Unknown scope " + String.join(", ", parsed.unknown()) + ". Scopes are: "
						+ Scope.vocabulary() + ".");
					return null;
				}
				scope = parsed.scopes();
			} else {
				if (none) {
					listen = EnumSet.noneOf(Listen.class);
					continue;
				}
				Listen.Parsed parsed = Listen.parse(list);
				if (!parsed.unknown().isEmpty()) {
					out.add("Unknown listen word " + String.join(", ", parsed.unknown()) + ". Words are: "
						+ Listen.vocabulary() + ".");
					return null;
				}
				listen = parsed.listens();
			}
		}
		if (required && scope == null && listen == null) {
			out.add("Say what the server may do: " + USAGE);
			return null;
		}
		return new Grants(scope, listen);
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
