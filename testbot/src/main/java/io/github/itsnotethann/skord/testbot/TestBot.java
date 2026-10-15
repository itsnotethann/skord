package io.github.itsnotethann.skord.testbot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

import ch.njol.skript.Skript;
import io.github.itsnotethann.skord.JdaEventBus;
import io.github.itsnotethann.skord.ScriptThread;
import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.event.ReadyEvent;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.syntax.BridgeSyntax;
import io.github.itsnotethann.skord.syntax.SkordSyntax;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.platform.AddonHandle;
import org.skriptlang.skript.platform.AddonRegistry;
import org.skriptlang.skript.platform.EventBus;
import org.skriptlang.skript.platform.LogSink;
import org.skriptlang.skript.platform.Platform;
import org.skriptlang.skript.platform.PlatformEnvironment;
import org.skriptlang.skript.platform.PlatformProvider;
import org.skriptlang.skript.platform.PlatformScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skord with a fake Discord: the real script thread, event bus, ack state machine and command sync, with
 * {@link FakeDiscord} where JDA would be. Boots like {@code SkordHost} (load, sync commands, go live, fire
 * {@code on ready}), waits for every script to record what its {@code .expected} file lists, and compares.
 * <p>
 * Arguments: the scripts folder, then a work folder (wiped of scripts and command state first). A script whose
 * name starts with {@code -} is staged disabled, for tests that enable it.
 */
public final class TestBot implements PlatformProvider {

	private static final long TIMEOUT_MILLIS = 30_000;
	private static final long SETTLE_MILLIS = 1_500;

	private static final Recorder RECORDER = new Recorder();

	private final File directory;
	private final ScriptThread scriptThread;
	private final EventBus eventBus;
	private final Logger logger = LoggerFactory.getLogger("Skript");
	private final PlatformEnvironment environment;

	TestBot(File directory, ScriptThread scriptThread, EventBus eventBus) {
		this.directory = directory;
		this.scriptThread = scriptThread;
		this.eventBus = eventBus;
		this.environment = new PlatformEnvironment() {
			@Override
			public String platformVersion() {
				return "1.21.0";
			}

			@Override
			public boolean isPrimaryThread() {
				return scriptThread.isPrimaryThread();
			}

			@Override
			public File serverDirectory() {
				return TestBot.this.directory;
			}

			@Override
			public Logger logger() {
				return logger;
			}
		};
	}

	static Recorder recorder() {
		return RECORDER;
	}

	public static void main(String[] args) throws Exception {
		Path scripts = Path.of(args[0]);
		Path work = Path.of(args[1]);
		Path serverScripts = args.length > 2 ? Path.of(args[2]) : null;
		ServerProcess.scripts(serverScripts);
		List<String> drift = ProtocolDrift.check();
		if (!drift.isEmpty()) {
			drift.forEach(problem -> System.out.println("BOTTEST FAIL: " + problem));
			System.exit(1);
		}
		stage(scripts, work);
		stageVariables(scripts, work);

		ScriptThread scriptThread = new ScriptThread();
		JdaEventBus eventBus = new JdaEventBus(scriptThread);
		Platform.install(new TestBot(work.toFile(), scriptThread, eventBus));
		FakeDiscord.bus(eventBus);
		Discord.install(FakeDiscord.INSTANCE);
		CommandSync.install(work.resolve(".skord").resolve("commands.hash"), 0, FakeDiscord.commandSink());
		TestBridge.start(work, scriptThread);

		CountDownLatch live = new CountDownLatch(1);
		Throwable[] failure = new Throwable[1];
		scriptThread.execute(() -> {
			try {
				Skript skript = new Skript();
				Skript.onRegistration(() -> {
					SkordSyntax.register();
					BridgeSyntax.register();
					TestSyntax.register();
				});
				Skript.onFinishedLoading(() -> scriptThread.execute(() -> {
					CommandSync.sync();
					eventBus.goLive();
					eventBus.fire(new ReadyEvent());
					live.countDown();
				}));
				skript.setEnabled(true);
				skript.onEnable();
				Skript.closeUnsafeSkript();
			} catch (Throwable throwable) {
				failure[0] = throwable;
				live.countDown();
			}
		});
		if (!live.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
			System.out.println("BOTTEST FAIL: scripts did not finish loading");
			System.exit(2);
		}
		if (failure[0] != null) {
			failure[0].printStackTrace();
			System.exit(2);
		}

		Map<String, Recorder.Expected> expected = readExpected(scripts, "");
		if (serverScripts != null) {
			expected.putAll(readExpected(serverScripts, "server/"));
			try (Stream<Path> profiles = Files.list(serverScripts)) {
				for (Path profile : profiles.filter(Files::isDirectory).sorted().toList())
					expected.putAll(readExpected(profile, "server/"));
			}
		}
		long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
		while (System.currentTimeMillis() < deadline && !RECORDER.reached(expected))
			Thread.sleep(50);
		// Long enough for anything that shouldn't happen (a late deferral, a stray request) to show up.
		Thread.sleep(SETTLE_MILLIS);

		ServerProcess.stopAll();
		CountDownLatch disabled = new CountDownLatch(1);
		scriptThread.execute(() -> {
			try {
				Skript skript = Skript.getInstance();
				if (skript.isEnabled()) {
					skript.onDisable();
					skript.setEnabled(false);
				}
			} finally {
				disabled.countDown();
			}
		});
		disabled.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

		int failures = RECORDER.compare(expected);
		scriptThread.shutdown();
		System.out.println(failures == 0 ? "BOTTEST PASS" : "BOTTEST FAIL: " + failures + " script(s)");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void stage(Path source, Path work) throws IOException {
		Path target = work.resolve("Skript").resolve("scripts");
		deleteTree(work.resolve(".skord"));
		deleteTree(target);
		Files.createDirectories(target);
		for (Path script : scripts(source, true))
			Files.copy(script, target.resolve(script.getFileName()), StandardCopyOption.REPLACE_EXISTING);
	}

	private static void stageVariables(Path scripts, Path work) throws IOException {
		Path fixture = scripts.toAbsolutePath().resolveSibling("fixtures").resolve("variables-pre3b.csv");
		Path target = work.resolve("Skript").resolve("variables.csv");
		if (Files.exists(fixture)) {
			Files.createDirectories(target.getParent());
			Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root))
			return;
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	private static List<Path> scripts(Path source, boolean includeDisabled) throws IOException {
		try (Stream<Path> files = Files.list(source)) {
			return files
				.filter(path -> path.getFileName().toString().endsWith(".sk"))
				.filter(path -> includeDisabled || !path.getFileName().toString().startsWith("-"))
				.sorted()
				.toList();
		}
	}

	private static Map<String, Recorder.Expected> readExpected(Path scripts, String prefix) throws IOException {
		Map<String, Recorder.Expected> expected = new TreeMap<>();
		for (Path script : scripts(scripts, false)) {
			String name = script.getFileName().toString();
			Path file = script.resolveSibling(name.substring(0, name.length() - 3) + ".expected");
			Recorder.Expected entry = new Recorder.Expected();
			if (Files.exists(file)) {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					if (line.isBlank())
						continue;
					if (line.startsWith("!"))
						entry.errors.add(line.substring(1).trim());
					else
						entry.marks.add(line);
				}
			}
			expected.put(prefix + name, entry);
		}
		return expected;
	}

	@Override
	public PlatformEnvironment environment() {
		return environment;
	}

	@Override
	public LogSink logSink() {
		return (level, message, error) -> {
			RECORDER.log(level, message);
			if (System.getProperty("skord.testserver") != null)
				System.out.println("LOG " + level + " " + message);
			if (level.intValue() >= Level.SEVERE.intValue())
				logger.error(message, error);
			else if (level.intValue() >= Level.WARNING.intValue())
				logger.warn(message, error);
			else
				logger.info(message, error);
		};
	}

	@Override
	public PlatformScheduler scheduler() {
		return scriptThread;
	}

	@Override
	public EventBus eventBus() {
		return eventBus;
	}

	@Override
	public AddonRegistry addonRegistry() {
		return new AddonRegistry() {
			@Override
			public @Nullable AddonHandle load(File jar) {
				return null;
			}

			@Override
			public List<AddonHandle> addons() {
				return List.of();
			}

			@Override
			public @Nullable AddonHandle providingAddon(Class<?> from) {
				return null;
			}
		};
	}
}
