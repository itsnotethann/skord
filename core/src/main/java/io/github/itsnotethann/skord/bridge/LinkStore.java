package io.github.itsnotethann.skord.bridge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The servers allowed to connect, in {@code .skord/links.yml}. Only the SHA-256 of each secret is kept: the
 * secret itself is shown once, by {@link #create}, and cannot be read back from the file.
 */
public final class LinkStore {

	public record Link(String name, String sha256, String created) {}

	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Path file;
	private final Map<String, Link> links = new TreeMap<>();

	public LinkStore(Path file) throws IOException {
		this.file = file;
		if (!Files.exists(file))
			return;
		Map<String, Object> root;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = new Yaml().load(reader);
		}
		if (root != null && root.get("links") instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getValue() instanceof Map<?, ?> value && value.get("sha256") != null) {
					String name = String.valueOf(entry.getKey());
					links.put(name, new Link(name, String.valueOf(value.get("sha256")),
						String.valueOf(value.get("created"))));
				}
			}
		}
	}

	public static boolean validName(String name) {
		return NAME.matcher(name).matches();
	}

	/** Creates a link and returns its secret, which is not stored. Returns null if the name is taken. */
	public synchronized @Nullable String create(String name) throws IOException {
		if (links.containsKey(name))
			return null;
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String secret = "skl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		links.put(name, new Link(name, hash(secret), Instant.now().toString()));
		save();
		return secret;
	}

	/** Removes a link. Returns false if there wasn't one. */
	public synchronized boolean revoke(String name) throws IOException {
		if (links.remove(name) == null)
			return false;
		save();
		return true;
	}

	public synchronized List<Link> list() {
		return List.copyOf(links.values());
	}

	public synchronized boolean exists(String name) {
		return links.containsKey(name);
	}

	/** Whether {@code secret} is the one issued for {@code name}. Compares in constant time. */
	public synchronized boolean authenticate(String name, String secret) {
		Link link = links.get(name);
		// Hash even when the name is unknown, so a miss takes as long as a wrong secret.
		byte[] given = hash(secret).getBytes(StandardCharsets.US_ASCII);
		byte[] wanted = (link == null ? hash("") : link.sha256()).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(given, wanted) && link != null;
	}

	private void save() throws IOException {
		Map<String, Object> entries = new LinkedHashMap<>();
		for (Link link : links.values()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sha256", link.sha256());
			entry.put("created", link.created());
			entries.put(link.name(), entry);
		}
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		String text = "# Servers allowed to connect to the bridge. Only hashes are kept; manage with 'sk link'.\n"
			+ new Yaml(options).dump(Map.of("links", entries));
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static String hash(String secret) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(secret.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
