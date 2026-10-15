package io.github.itsnotethann.skord;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.swing.JOptionPane;

/**
 * The jar's entry point, compiled for Java 8 so that any Java can at least run it.
 * <p>
 * Double-clicking a jar on Windows runs it with {@code javaw}: no console window, the working directory left to
 * chance, and often an old Java the file association still points at. skord then fails silently, or runs with no
 * console to type into and nothing to stop. So when the jar wasn't started from a terminal, this reopens it in a
 * console window, in the jar's own folder, with a Java new enough to run it. Started from a terminal (or a
 * service), it hands straight over to {@link SkordHost}.
 */
public final class Launcher {

	private static final int REQUIRED_JAVA = 25;
	/** Set on the relaunched process, so it never relaunches again. */
	private static final String LAUNCHED = "skord.launched";

	private Launcher() {
	}

	public static void main(String[] args) throws Throwable {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
		boolean javaOk = javaVersion() >= REQUIRED_JAVA;

		if (windows && System.getProperty(LAUNCHED) == null && (!javaOk || isJavaw())) {
			if (relaunch(args, javaOk))
				return;
		}
		if (!javaOk) {
			String message = "skord needs Java " + REQUIRED_JAVA + " or newer, but this is Java "
				+ System.getProperty("java.version") + " (" + System.getProperty("java.home") + ").\n"
				+ "Install Java " + REQUIRED_JAVA + " from https://adoptium.net and run: java -jar skord.jar";
			System.err.println(message);
			if (System.console() == null && !java.awt.GraphicsEnvironment.isHeadless())
				JOptionPane.showMessageDialog(null, message, "skord", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
			return;
		}
		// Reflection, so this class never links against Java 25 bytecode when an old Java loads it.
		Method main = Class.forName("io.github.itsnotethann.skord.SkordHost").getMethod("main", String[].class);
		try {
			main.invoke(null, (Object) args);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw e.getCause();
		}
	}

	// Opens a console window that keeps showing after skord exits, so a first start or an error stays readable.
	private static boolean relaunch(String[] args, boolean javaOk) {
		File jar;
		try {
			jar = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (Exception e) {
			return false;
		}
		// This Java is new enough: reuse it, as java.exe rather than javaw.exe. Otherwise trust the PATH; if the
		// java there is old too, the relaunched process says so in its window.
		String java = javaOk ? new File(System.getProperty("java.home"), "bin\\java.exe").getPath() : "java";
		List<String> command = new ArrayList<>();
		command.add("cmd.exe");
		command.add("/c");
		command.add("start");
		command.add("\"skord\""); // start's first quoted argument is the window title
		command.add("cmd.exe");
		command.add("/k");
		command.add(java);
		command.add("-D" + LAUNCHED + "=true");
		command.add("-jar");
		command.add(jar.getName());
		for (String arg : args)
			command.add(arg);
		try {
			new ProcessBuilder(command).directory(jar.getParentFile()).start();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static int javaVersion() {
		String version = System.getProperty("java.specification.version", "0");
		if (version.startsWith("1."))
			version = version.substring(2);
		try {
			return Integer.parseInt(version);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ProcessHandle is Java 9, so it's reached by reflection. Only asked once Java is new enough anyway.
	private static boolean isJavaw() {
		try {
			Class<?> handle = Class.forName("java.lang.ProcessHandle");
			Object current = handle.getMethod("current").invoke(null);
			Object info = handle.getMethod("info").invoke(current);
			// Through the interface, not info's class: the implementation class isn't accessible.
			@SuppressWarnings("unchecked")
			Optional<Object> command = (Optional<Object>) Class.forName("java.lang.ProcessHandle$Info")
				.getMethod("command").invoke(info);
			return String.valueOf(command.orElse("")).toLowerCase(Locale.ROOT).endsWith("javaw.exe");
		} catch (Exception e) {
			return false;
		}
	}
}
