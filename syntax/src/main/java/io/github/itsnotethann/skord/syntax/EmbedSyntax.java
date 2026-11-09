package io.github.itsnotethann.skord.syntax;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.Date;
import ch.njol.util.Kleenean;
import io.github.itsnotethann.skord.discord.Embed;
import io.github.itsnotethann.skord.discord.Embeds;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.event.PlatformEvent;

/**
 * Embeds, in the shape menus and modals use: an expression makes one, and effects and settable properties change
 * it. {@link Embed} is immutable, so a change sets the variable to a changed copy. Discord's limits are checked when
 * a message is sent, not here, so an embed can be built up in any order.
 */
final class EmbedSyntax {

	private EmbedSyntax() {}

	/** The parts {@link ExprEmbedPart} reads and sets, in its pattern's order. */
	enum Part {
		TITLE("title"), DESCRIPTION("description"), URL("url"), COLOR("colour"), AUTHOR_NAME("author name"),
		AUTHOR_ICON("author icon"), AUTHOR_URL("author url"), FOOTER_TEXT("footer"), FOOTER_ICON("footer icon"),
		IMAGE("image"), THUMBNAIL("thumbnail");

		final String label;

		Part(String label) {
			this.label = label;
		}
	}

	private static final String PARTS = "(0:title|1:description|2:url|3:colo[u]r|4:author name|5:author icon|6:author url"
		+ "|7:footer [text]|8:footer icon|9:image|10:thumbnail)";

	static void register() {
		Classes.registerClass(new ClassInfo<>(Embed.class, "discordembed")
			.user("(discord )?embeds?")
			.name("Discord Embed")
			.description("A rich embed to put on a message with 'with embed'. Build one with 'an embed titled \"x\"', "
				+ "then set its parts ('set description of {_embed} to ...') and add fields ('add field \"Name\" with "
				+ "value \"...\" to {_embed}').")
			.parser(new Parser<>() {
				@Override
				public boolean canParse(ParseContext context) {
					return false;
				}

				@Override
				public String toString(Embed embed, int flags) {
					return embed.title() != null ? embed.title() : "embed";
				}

				@Override
				public String toVariableNameString(Embed embed) {
					return "embed:" + (embed.title() == null ? "" : embed.title());
				}
			}));
		Skript.registerExpression(ExprEmbed.class, Embed.class, ExpressionType.COMBINED, "[a[n]] [new] embed [titled %-string%]");
		Skript.registerExpression(ExprEmbedPart.class, String.class, ExpressionType.PROPERTY,
			"[the] [embed] " + PARTS + " of %discordembed%", "%discordembed%'[s] " + PARTS);
		Skript.registerExpression(ExprEmbedTimestamp.class, Date.class, ExpressionType.PROPERTY,
			"[the] [embed] timestamp of %discordembed%", "%discordembed%'[s] timestamp");
		Skript.registerEffect(EffAddField.class,
			"add [a[n]] [inline:inline] field [named] %string% with value %string% to %~discordembed%");
	}

	/** {@code [a] [new] embed [titled "..."]}: an embed with nothing else set. */
	public static final class ExprEmbed extends SimpleExpression<Embed> {

		private @Nullable Expression<String> title;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			title = (Expression<String>) exprs[0];
			if (title instanceof Literal<String> literal) {
				String value = literal.getSingle();
				if (value != null && value.length() > Embed.MAX_TITLE) {
					Skript.error("An embed's title can be at most " + Embed.MAX_TITLE + " characters");
					return false;
				}
			}
			return true;
		}

		@Override
		protected Embed @Nullable [] get(PlatformEvent event) {
			if (title == null)
				return new Embed[] {new Embed()};
			String value = title.getSingle(event);
			return new Embed[] {Embed.titled(value == null || value.isEmpty() ? null : value)};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Embed> getReturnType() {
			return Embed.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "embed" + (title == null ? "" : " titled " + title.toString(event, debug));
		}
	}

	/**
	 * One text part of an embed, readable and settable: {@code set description of {_embed} to "..."}. Setting sets the
	 * variable to a copy with that part changed; {@code delete} unsets it. The colour reads as {@code #rrggbb} and is
	 * set from a hex text, a colour name ({@code red}, {@code blurple}) or a number.
	 */
	public static final class ExprEmbedPart extends SimpleExpression<String> {

		private Expression<Embed> embed;
		private Part part;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			embed = (Expression<Embed>) exprs[0];
			part = Part.values()[parseResult.mark];
			return true;
		}

		@Override
		protected String @Nullable [] get(PlatformEvent event) {
			Embed value = embed.getSingle(event);
			if (value == null)
				return null;
			String out = switch (part) {
				case TITLE -> value.title();
				case DESCRIPTION -> value.description();
				case URL -> value.url();
				case COLOR -> value.color() == null ? null : Embeds.showColor(value.color());
				case AUTHOR_NAME -> value.authorName();
				case AUTHOR_ICON -> value.authorIcon();
				case AUTHOR_URL -> value.authorUrl();
				case FOOTER_TEXT -> value.footerText();
				case FOOTER_ICON -> value.footerIcon();
				case IMAGE -> value.image();
				case THUMBNAIL -> value.thumbnail();
			};
			return out == null ? null : new String[] {out};
		}

		@Override
		public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
			if (embed.acceptChange(ChangeMode.SET) == null)
				return null;
			return switch (mode) {
				case SET -> part == Part.COLOR ? new Class<?>[] {String.class, Number.class} : new Class<?>[] {String.class};
				case DELETE, RESET -> new Class<?>[0];
				default -> null;
			};
		}

		@Override
		public void change(PlatformEvent event, Object @Nullable [] delta, ChangeMode mode) {
			Embed current = embed.getSingle(event);
			if (current == null)
				return;
			Object given = mode == ChangeMode.SET && delta != null && delta.length > 0 ? delta[0] : null;
			String text = given instanceof String string && !string.isEmpty() ? string : null;
			Embed changed = switch (part) {
				case TITLE -> current.withTitle(text);
				case DESCRIPTION -> current.withDescription(text);
				case URL -> current.withUrl(text);
				case COLOR -> {
					Integer color = null;
					if (given instanceof Number number) {
						color = number.intValue() & 0xFFFFFF;
					} else if (text != null) {
						color = Embeds.parseColor(text);
						if (color == null) {
							Skript.warning("'" + text + "' isn't a colour; use a hex colour such as #5865F2 or a name such as "
								+ "red, blue or blurple. The embed's colour was left as it was.");
							yield current;
						}
					}
					yield current.withColor(color);
				}
				case AUTHOR_NAME -> current.withAuthorName(text);
				case AUTHOR_ICON -> current.withAuthorIcon(text);
				case AUTHOR_URL -> current.withAuthorUrl(text);
				case FOOTER_TEXT -> current.withFooterText(text);
				case FOOTER_ICON -> current.withFooterIcon(text);
				case IMAGE -> current.withImage(text);
				case THUMBNAIL -> current.withThumbnail(text);
			};
			embed.change(event, new Object[] {changed}, ChangeMode.SET);
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends String> getReturnType() {
			return String.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return part.label + " of " + embed.toString(event, debug);
		}
	}

	/** The embed's timestamp, the time Discord shows beside its footer: {@code set timestamp of {_embed} to now}. */
	public static final class ExprEmbedTimestamp extends SimpleExpression<Date> {

		private Expression<Embed> embed;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			embed = (Expression<Embed>) exprs[0];
			return true;
		}

		@Override
		protected Date @Nullable [] get(PlatformEvent event) {
			Embed value = embed.getSingle(event);
			return value == null || value.timestamp() == null ? null : new Date[] {new Date(value.timestamp())};
		}

		@Override
		public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
			if (embed.acceptChange(ChangeMode.SET) == null)
				return null;
			return switch (mode) {
				case SET -> new Class<?>[] {Date.class};
				case DELETE, RESET -> new Class<?>[0];
				default -> null;
			};
		}

		@Override
		public void change(PlatformEvent event, Object @Nullable [] delta, ChangeMode mode) {
			Embed current = embed.getSingle(event);
			if (current == null)
				return;
			Long time = mode == ChangeMode.SET && delta != null && delta.length > 0 && delta[0] instanceof Date date
				? date.getTimestamp() : null;
			embed.change(event, new Object[] {current.withTimestamp(time)}, ChangeMode.SET);
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Date> getReturnType() {
			return Date.class;
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "timestamp of " + embed.toString(event, debug);
		}
	}

	/**
	 * {@code add [inline] field "Name" with value "..." to {_embed}}. Past 25 fields the field is still added, and the
	 * message that carries the embed fails with an error naming the limit, so nothing is silently dropped.
	 */
	public static final class EffAddField extends Effect {

		private Expression<String> name;
		private Expression<String> value;
		private Expression<Embed> embed;
		private boolean inline;

		@Override
		@SuppressWarnings("unchecked")
		public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			name = (Expression<String>) exprs[0];
			value = (Expression<String>) exprs[1];
			embed = (Expression<Embed>) exprs[2];
			inline = parseResult.hasTag("inline");
			if (!embed.isSingle()) {
				Skript.error("Fields are added to one embed at a time");
				return false;
			}
			return true;
		}

		@Override
		protected void execute(PlatformEvent event) {
			Embed current = embed.getSingle(event);
			String nameValue = name.getSingle(event);
			String valueValue = value.getSingle(event);
			if (current == null || nameValue == null || valueValue == null)
				return;
			embed.change(event, new Object[] {current.withField(new Embed.Field(nameValue, valueValue, inline))}, ChangeMode.SET);
		}

		@Override
		public String toString(@Nullable PlatformEvent event, boolean debug) {
			return "add " + (inline ? "inline " : "") + "field " + name.toString(event, debug) + " with value "
				+ value.toString(event, debug) + " to " + embed.toString(event, debug);
		}
	}
}
