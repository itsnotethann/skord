package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One server's held events, in {@code .skord/held/<server>.jsonl}: an append-only log of add, sent, ack and drop
 * records, folded back into the queue at load. An id that was acked is remembered (the last {@link #ACKED_KEPT}) so a
 * second delivery of the same Discord event is refused.
 */
public final class HeldQueue {

	static final int ACKED_KEPT = 2_000;

	private static final Logger LOGGER = LoggerFactory.getLogger("skord");

	public record Entry(String id, String kind, JsonObject data, long at, boolean sent) {}

	private final String server;
	private final Path file;
	private final int maxEvents;
	private final long maxAgeMillis;
	private final Map<String, Entry> queue = new LinkedHashMap<>();
	private final Set<String> acked = new LinkedHashSet<>();
	private final List<String> drops = new ArrayList<>();

	public HeldQueue(String server, Path file, int maxEvents, long maxAgeMillis) {
		this.server = server;
		this.file = file;
		this.maxEvents = maxEvents;
		this.maxAgeMillis = maxAgeMillis;
		load();
	}

	private void load() {
		if (!Files.exists(file))
			return;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank())
					continue;
				try {
					JsonObject record = JsonParser.parseString(line).getAsJsonObject();
					String id = record.get("id").getAsString();
					switch (record.get("op").getAsString()) {
						case "add" -> queue.put(id, new Entry(id, record.get("kind").getAsString(),
							record.get("data").getAsJsonObject(), record.get("at").getAsLong(),
							record.has("sent") && record.get("sent").getAsBoolean()));
						case "sent" -> {
							Entry entry = queue.get(id);
							if (entry != null)
								queue.put(id, new Entry(id, entry.kind(), entry.data(), entry.at(), true));
						}
						case "ack" -> {
							queue.remove(id);
							remember(id);
						}
						case "drop" -> queue.remove(id);
						default -> {}
					}
				} catch (RuntimeException e) {
					LOGGER.warn("Held queue of '{}': skipped a damaged line: {}", server, e.getMessage());
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read {}: {}", file, e.getMessage());
		}
		compact();
	}

	private void compact() {
		StringBuilder out = new StringBuilder();
		for (String id : acked)
			out.append(line("ack", id, null, null, 0, false));
		for (Entry entry : queue.values())
			out.append(line("add", entry.id(), entry.kind(), entry.data(), entry.at(), entry.sent()));
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Could not compact {}: {}", file, e.getMessage());
		}
	}

	private static String line(String op, String id, @Nullable String kind, @Nullable JsonObject data, long at,
							   boolean sent) {
		JsonObject record = new JsonObject();
		record.addProperty("op", op);
		record.addProperty("id", id);
		if (kind != null) {
			record.addProperty("kind", kind);
			record.add("data", data);
			record.addProperty("at", at);
			if (sent)
				record.addProperty("sent", true);
		}
		return record + "\n";
	}

	private void append(String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("Could not write {}: events may be lost on a restart: {}", file, e.getMessage());
		}
	}

	private void remember(String id) {
		acked.remove(id);
		acked.add(id);
		while (acked.size() > ACKED_KEPT)
			acked.remove(acked.iterator().next());
	}

	synchronized boolean seen(String id) {
		return queue.containsKey(id) || acked.contains(id);
	}

	/** Adds an event unless its id was already queued or acked. Drops the oldest past the caps, logging each. */
	public synchronized boolean add(String id, String kind, JsonObject data, long now) {
		if (seen(id))
			return false;
		queue.put(id, new Entry(id, kind, data, now, false));
		append(line("add", id, kind, data, now, false));
		expire(now);
		while (queue.size() > maxEvents) {
			Entry oldest = queue.values().iterator().next();
			drop(oldest, "the queue is past bridge.held.max-events (" + maxEvents + ")");
		}
		return queue.containsKey(id);
	}

	synchronized void expire(long now) {
		for (Entry entry : new ArrayList<>(queue.values())) {
			if (now - entry.at() > maxAgeMillis)
				drop(entry, "it is older than bridge.held.max-age");
		}
	}

	private void drop(Entry entry, String why) {
		queue.remove(entry.id());
		append(line("drop", entry.id(), null, null, 0, false));
		String message = "dropped event " + entry.id() + " (" + entry.kind() + ") because " + why;
		drops.add(message);
		LOGGER.warn("Held queue of '{}': {}", server, message);
	}

	public synchronized List<Entry> pending(long now) {
		expire(now);
		return new ArrayList<>(queue.values());
	}

	public synchronized void markSent(String id) {
		Entry entry = queue.get(id);
		if (entry == null || entry.sent())
			return;
		queue.put(id, new Entry(id, entry.kind(), entry.data(), entry.at(), true));
		append(line("sent", id, null, null, 0, false));
	}

	public synchronized boolean ack(String id) {
		boolean was = queue.remove(id) != null;
		if (was || !acked.contains(id)) {
			remember(id);
			append(line("ack", id, null, null, 0, false));
		}
		return was;
	}

	synchronized int size() {
		return queue.size();
	}

	public synchronized List<String> drops() {
		return new ArrayList<>(drops);
	}

	public synchronized List<String> ids() {
		return new ArrayList<>(queue.keySet());
	}

	synchronized void clear() {
		queue.clear();
		acked.clear();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOGGER.warn("Could not delete {}: {}", file, e.getMessage());
		}
	}
}
