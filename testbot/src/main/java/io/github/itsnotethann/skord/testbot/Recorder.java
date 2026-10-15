package io.github.itsnotethann.skord.testbot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * What each script did, in order: its {@code mark}s and the requests the fake Discord received on its behalf.
 * Also collects every warning and error, so one nobody expected fails the run.
 */
final class Recorder {

	private final Map<String, List<String>> marks = new ConcurrentHashMap<>();
	private final List<String> problems = new ArrayList<>();

	void mark(String script, String text) {
		System.out.println("MARK " + script + ": " + text);
		List<String> list = marks.computeIfAbsent(script, key -> new ArrayList<>());
		synchronized (list) {
			list.add(text);
		}
	}

	void log(Level level, String message) {
		if (level.intValue() < Level.WARNING.intValue())
			return;
		synchronized (problems) {
			problems.add(message);
		}
	}

	private List<String> marks(String script) {
		List<String> list = marks.get(script);
		if (list == null)
			return List.of();
		synchronized (list) {
			return new ArrayList<>(list);
		}
	}

	boolean reached(Map<String, Expected> expected) {
		for (Map.Entry<String, Expected> entry : expected.entrySet()) {
			if (marks(entry.getKey()).size() < entry.getValue().marks.size())
				return false;
		}
		return true;
	}

	int compare(Map<String, Expected> expected) {
		List<String> logged;
		synchronized (problems) {
			logged = new ArrayList<>(problems);
		}
		Set<String> unclaimed = new LinkedHashSet<>(logged);
		int failures = 0;
		for (Map.Entry<String, Expected> entry : expected.entrySet()) {
			String script = entry.getKey();
			Expected want = entry.getValue();
			List<String> got = marks(script);
			List<String> report = new ArrayList<>();
			if (!got.equals(want.marks)) {
				int size = Math.max(got.size(), want.marks.size());
				for (int i = 0; i < size; i++) {
					String g = i < got.size() ? got.get(i) : "<missing>";
					String w = i < want.marks.size() ? want.marks.get(i) : "<none>";
					report.add((g.equals(w) ? "    " : "  ! ") + "expected [" + w + "] got [" + g + "]");
				}
			}
			for (String error : want.errors) {
				String match = logged.stream().filter(line -> line.contains(error)).findFirst().orElse(null);
				if (match == null)
					report.add("  ! expected a warning or error containing [" + error + "]");
				else
					unclaimed.remove(match);
			}
			if (report.isEmpty()) {
				System.out.println("PASS " + script);
			} else {
				failures++;
				System.out.println("FAIL " + script);
				report.forEach(System.out::println);
			}
		}
		for (String script : marks.keySet()) {
			if (!expected.containsKey(script)) {
				failures++;
				System.out.println("FAIL " + script + " recorded something but has no .expected file");
			}
		}
		if (!unclaimed.isEmpty()) {
			failures++;
			System.out.println("FAIL unexpected warnings or errors:");
			unclaimed.forEach(line -> System.out.println("  ! " + line));
		}
		return failures;
	}

	/** A script's {@code .expected} file: one line per mark, and {@code !} lines for expected warnings. */
	static final class Expected {
		final List<String> marks = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
	}
}
