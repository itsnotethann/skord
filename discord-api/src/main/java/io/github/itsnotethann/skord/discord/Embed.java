package io.github.itsnotethann.skord.discord;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * A rich embed under a message: a title, a description, a colour bar, an author line, a footer, images and up to 25
 * fields. Immutable; every {@code with...} method returns a copy, which is how scripts change an embed held in a
 * variable. It holds whatever a script put in it, past Discord's limits too: {@link Embeds#problem} checks the
 * limits when a message is about to be sent, so an over-long embed fails that message instead of being cut.
 *
 * @param color     the colour bar as {@code 0xRRGGBB}, or null for none
 * @param timestamp milliseconds since the epoch, or null for none
 */
public record Embed(@Nullable String title, @Nullable String description, @Nullable String url, @Nullable Integer color,
					@Nullable String authorName, @Nullable String authorIcon, @Nullable String authorUrl,
					@Nullable String footerText, @Nullable String footerIcon, @Nullable String image,
					@Nullable String thumbnail, @Nullable Long timestamp, List<Field> fields) {

	public static final int MAX_TITLE = 256;
	public static final int MAX_DESCRIPTION = 4096;
	public static final int MAX_FIELDS = 25;
	public static final int MAX_FIELD_NAME = 256;
	public static final int MAX_FIELD_VALUE = 1024;
	public static final int MAX_FOOTER = 2048;
	public static final int MAX_AUTHOR = 256;
	/** Discord's limit on the text of all the embeds of one message together. */
	public static final int MAX_TOTAL = 6000;
	/** Discord's limit on embeds in one message. */
	public static final int MAX_PER_MESSAGE = 10;

	/** One name/value pair. Inline fields sit side by side, up to three to a line. */
	public record Field(String name, String value, boolean inline) {}

	public Embed {
		fields = List.copyOf(fields);
	}

	/** An embed with nothing set. */
	public Embed() {
		this(null, null, null, null, null, null, null, null, null, null, null, null, List.of());
	}

	public static Embed titled(@Nullable String title) {
		return new Embed().withTitle(title);
	}

	public Embed withTitle(@Nullable String value) {
		return new Embed(value, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withDescription(@Nullable String value) {
		return new Embed(title, value, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withUrl(@Nullable String value) {
		return new Embed(title, description, value, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withColor(@Nullable Integer value) {
		return new Embed(title, description, url, value, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withAuthorName(@Nullable String value) {
		return new Embed(title, description, url, color, value, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withAuthorIcon(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, value, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withAuthorUrl(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, authorIcon, value, footerText, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withFooterText(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, value, footerIcon, image,
			thumbnail, timestamp, fields);
	}

	public Embed withFooterIcon(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, value, image,
			thumbnail, timestamp, fields);
	}

	public Embed withImage(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, value,
			thumbnail, timestamp, fields);
	}

	public Embed withThumbnail(@Nullable String value) {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			value, timestamp, fields);
	}

	public Embed withTimestamp(@Nullable Long value) {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, value, fields);
	}

	public Embed withField(Field field) {
		List<Field> more = new ArrayList<>(fields);
		more.add(field);
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, more);
	}

	public Embed withoutFields() {
		return new Embed(title, description, url, color, authorName, authorIcon, authorUrl, footerText, footerIcon, image,
			thumbnail, timestamp, List.of());
	}

	/** The characters Discord counts towards {@link #MAX_TOTAL}: title, description, field names and values, footer text and author name. */
	public int length() {
		int total = length(title) + length(description) + length(footerText) + length(authorName);
		for (Field field : fields)
			total += field.name().length() + field.value().length();
		return total;
	}

	/** Whether nothing that shows is set. Discord refuses an empty embed. */
	public boolean isEmpty() {
		return title == null && description == null && authorName == null && footerText == null && image == null
			&& thumbnail == null && timestamp == null && fields.isEmpty();
	}

	private static int length(@Nullable String text) {
		return text == null ? 0 : text.length();
	}
}
