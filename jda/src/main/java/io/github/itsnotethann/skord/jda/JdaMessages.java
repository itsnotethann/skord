package io.github.itsnotethann.skord.jda;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import io.github.itsnotethann.skord.discord.Embed;
import io.github.itsnotethann.skord.discord.MessageBody;
import io.github.itsnotethann.skord.interaction.JdaResponder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.jetbrains.annotations.Nullable;

/**
 * Message bodies as JDA builds them, and JDA's embeds as skord's. Every message skord sends goes through
 * {@link #create}, so mentions are off unless the script allowed them: the allowed-mentions list is set on each
 * message rather than through JDA's global default, which an embedded bot shares with its server.
 */
public final class JdaMessages {

	private JdaMessages() {}

	public static MessageCreateData create(MessageBody body) {
		MessageCreateBuilder builder = new MessageCreateBuilder().setContent(body.text());
		if (body.embeds() != null)
			builder.setEmbeds(embeds(body.embeds()));
		if (body.components() != null)
			builder.setComponents(JdaResponder.rows(body.components()));
		builder.setAllowedMentions(mentions(body.mentions()));
		builder.mentionRepliedUser(body.mentions());
		return builder.build();
	}

	/** Sets the text always, and embeds and components only when the body does, so the rest of the message stays. */
	public static MessageEditData edit(MessageBody body) {
		MessageEditBuilder builder = new MessageEditBuilder().setContent(body.text());
		if (body.embeds() != null)
			builder.setEmbeds(embeds(body.embeds()));
		if (body.components() != null)
			builder.setComponents(JdaResponder.rows(body.components()));
		builder.setAllowedMentions(mentions(body.mentions()));
		return builder.build();
	}

	private static EnumSet<Message.MentionType> mentions(boolean allowed) {
		return allowed ? EnumSet.allOf(Message.MentionType.class) : EnumSet.noneOf(Message.MentionType.class);
	}

	public static List<MessageEmbed> embeds(List<Embed> embeds) {
		List<MessageEmbed> out = new ArrayList<>();
		for (Embed embed : embeds)
			out.add(embed(embed));
		return out;
	}

	public static MessageEmbed embed(Embed embed) {
		EmbedBuilder builder = new EmbedBuilder();
		if (embed.title() != null)
			builder.setTitle(embed.title(), embed.url());
		else if (embed.url() != null)
			builder.setUrl(embed.url());
		if (embed.description() != null)
			builder.setDescription(embed.description());
		if (embed.color() != null)
			builder.setColor(embed.color());
		if (embed.authorName() != null)
			builder.setAuthor(embed.authorName(), embed.authorUrl(), embed.authorIcon());
		if (embed.footerText() != null)
			builder.setFooter(embed.footerText(), embed.footerIcon());
		if (embed.image() != null)
			builder.setImage(embed.image());
		if (embed.thumbnail() != null)
			builder.setThumbnail(embed.thumbnail());
		if (embed.timestamp() != null)
			builder.setTimestamp(Instant.ofEpochMilli(embed.timestamp()));
		for (Embed.Field field : embed.fields())
			builder.addField(field.name(), field.value(), field.inline());
		return builder.build();
	}

	public static List<Embed> fromJda(List<MessageEmbed> embeds) {
		List<Embed> out = new ArrayList<>();
		for (MessageEmbed embed : embeds)
			out.add(fromJda(embed));
		return out;
	}

	public static Embed fromJda(MessageEmbed embed) {
		List<Embed.Field> fields = new ArrayList<>();
		for (MessageEmbed.Field field : embed.getFields())
			fields.add(new Embed.Field(text(field.getName()), text(field.getValue()), field.isInline()));
		MessageEmbed.AuthorInfo author = embed.getAuthor();
		MessageEmbed.Footer footer = embed.getFooter();
		int color = embed.getColorRaw();
		return new Embed(embed.getTitle(), embed.getDescription(), embed.getUrl(),
			color == net.dv8tion.jda.api.entities.Role.DEFAULT_COLOR_RAW ? null : color & 0xFFFFFF,
			author == null ? null : author.getName(), author == null ? null : author.getIconUrl(),
			author == null ? null : author.getUrl(), footer == null ? null : footer.getText(),
			footer == null ? null : footer.getIconUrl(), embed.getImage() == null ? null : embed.getImage().getUrl(),
			embed.getThumbnail() == null ? null : embed.getThumbnail().getUrl(),
			embed.getTimestamp() == null ? null : embed.getTimestamp().toInstant().toEpochMilli(), fields);
	}

	private static String text(@Nullable String value) {
		return value == null ? "" : value;
	}
}
