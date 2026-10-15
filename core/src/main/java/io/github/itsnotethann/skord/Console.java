package io.github.itsnotethann.skord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.bridge.BridgeHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console, modelled on Skript's {@code /sk} command so it feels the same:
 * {@code sk reload <all|config|script|folder>}, {@code sk enable|disable <script>}, {@code sk list}, {@code stop}.
 * The {@code sk} prefix is optional.
 * <p>
 * Everything runs on the script thread, through {@link Scripts}, which also re-syncs slash commands. Script
 * errors print through the normal log, so there's no log redirection here the way an in-game command needs.
 */
public final class Console {

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	private static final String HELP = """
		Commands (the "sk" prefix is optional):
		  sk reload all              reload config.sk and every script
		  sk reload config           reload config.sk
		  sk reload <script|folder>  reload one script, or every script in a folder
		  sk enable <script>         enable and load a disabled script
		  sk disable <script>        unload a script and disable it
		  sk list                    list loaded and disabled scripts
		  sk link create <server>    allow a game server to connect; prints its secret once
		  sk link revoke <server>    cut a server off and forget its secret
		  sk link list               list linked servers and whether they are connected
		  stop                       shut down""";

	private final ScriptThread scriptThread;

	Console(ScriptThread scriptThread) {
		this.scriptThread = scriptThread;
	}

	/** Reads commands until stdin closes. Without stdin (a service, a container) this returns at once. */
	void run() throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			String command = line;
			if (command.isBlank())
				continue;
			if (isStop(command))
				System.exit(0); // runs the shutdown hook
			scriptThread.execute(() -> dispatch(command));
		}
	}

	private static boolean isStop(String line) {
		String[] args = line.trim().split("\\s+");
		return args[args[0].equalsIgnoreCase("sk") && args.length > 1 ? 1 : 0].equalsIgnoreCase("stop");
	}

	/**
	 * Runs one console line, other than {@code stop}. Call on the script thread. The future completes when the
	 * command, including any reload and command sync it causes, has finished.
	 */
	public static CompletableFuture<Void> dispatch(String line) {
		String[] args = line.trim().split("\\s+", 3);
		int at = args[0].equalsIgnoreCase("sk") || args[0].equalsIgnoreCase("skript") ? 1 : 0;
		String command = at < args.length ? args[at].toLowerCase(Locale.ROOT) : "help";
		String argument = at + 1 < args.length ? String.join(" ", Arrays.copyOfRange(args, at + 1, args.length)) : null;
		return switch (command) {
			case "reload" -> argument == null ? usage("Usage: sk reload <all|config|script|folder>") : Scripts.reload(argument);
			case "enable" -> argument == null ? usage("Usage: sk enable <script>") : Scripts.enable(argument);
			case "disable" -> argument == null ? usage("Usage: sk disable <script>") : Scripts.disable(argument);
			case "link" -> {
				link(argument);
				yield CompletableFuture.completedFuture(null);
			}
			case "list" -> {
				list();
				yield CompletableFuture.completedFuture(null);
			}
			default -> usage(HELP);
		};
	}

	// Printed straight to stdout, not through the log: a new secret must not end up in a log file.
	private static void link(String argument) {
		BridgeHost bridge = BridgeHost.installed();
		if (bridge == null) {
			System.out.println("The bridge isn't available in this mode.");
			return;
		}
		String[] args = argument == null ? new String[0] : argument.trim().split("\s+");
		bridge.link(args).forEach(System.out::println);
		if (bridge.server() == null)
			System.out.println("(The bridge is off: set bridge.port in skord.yml for servers to connect.)");
	}

	private static CompletableFuture<Void> usage(String text) {
		LOGGER.info(text);
		return CompletableFuture.completedFuture(null);
	}

	private static void list() {
		Path root = Skript.getInstance().getScriptsFolder().toPath();
		StringBuilder out = new StringBuilder("Scripts in ").append(root).append(':');
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(path -> path.getFileName().toString().endsWith(".sk"))
				.sorted(Comparator.comparing(Path::toString))
				.forEach(path -> {
					boolean off = path.getFileName().toString().startsWith(ScriptLoader.DISABLED_SCRIPT_PREFIX);
					out.append("\n  ").append(off ? "[disabled] " : "[loaded]   ")
						.append(root.relativize(path).toString().replace('\\', '/'));
				});
		} catch (IOException e) {
			LOGGER.error("Could not list scripts", e);
			return;
		}
		LOGGER.info(out.toString());
	}
}
