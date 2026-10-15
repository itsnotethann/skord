package io.github.itsnotethann.skord.jda;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.itsnotethann.skord.JdaEventBus;
import io.github.itsnotethann.skord.SkordHost;
import io.github.itsnotethann.skord.command.JdaSpecs;
import io.github.itsnotethann.skord.discord.CategoryRef;
import io.github.itsnotethann.skord.discord.ChannelRef;
import io.github.itsnotethann.skord.discord.Discord;
import io.github.itsnotethann.skord.discord.DiscordActions;
import io.github.itsnotethann.skord.discord.DiscordError;
import io.github.itsnotethann.skord.discord.DiscordEvent;
import io.github.itsnotethann.skord.discord.DiscordEvents;
import io.github.itsnotethann.skord.discord.DiscordException;
import io.github.itsnotethann.skord.discord.DiscordRef;
import io.github.itsnotethann.skord.discord.EventSpec;
import io.github.itsnotethann.skord.discord.FetchProperty;
import io.github.itsnotethann.skord.discord.GuildRef;
import io.github.itsnotethann.skord.discord.MemberRef;
import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.RoleRef;
import io.github.itsnotethann.skord.discord.UserRef;
import io.github.itsnotethann.skord.interaction.JdaResponder;
import io.github.itsnotethann.skord.protocol.CommandSpec;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.Nullable;

public final class JdaDiscord implements Discord, DiscordActions, DiscordEvents {

	private final JdaEventBus bus;

	public JdaDiscord(JdaEventBus bus) {
		this.bus = bus;
	}

	private static JDA jda() {
		return SkordHost.jda();
	}

	@Override
	public DiscordActions actions() {
		return this;
	}

	@Override
	public DiscordEvents events() {
		return this;
	}

	@Override
	public Subscription subscribe(EventSpec spec, Consumer<DiscordEvent> handler) {
		return bus.subscribe(spec, handler);
	}

	@Override
	public UserRef self() {
		return JdaRefs.user(jda().getSelfUser());
	}

	@Override
	public @Nullable ChannelRef cachedChannel(long id) {
		MessageChannel channel = jda().getChannelById(MessageChannel.class, id);
		return channel == null ? null : JdaRefs.channel(channel);
	}

	@Override
	public @Nullable CategoryRef cachedCategory(long id) {
		Category category = jda().getCategoryById(id);
		return category == null ? null : JdaRefs.category(category);
	}

	@Override
	public @Nullable String cachedName(DiscordRef ref) {
		return switch (ref) {
			case UserRef user -> {
				User cached = jda().getUserById(user.id());
				yield cached == null ? null : cached.getName();
			}
			case MemberRef member -> {
				Guild guild = jda().getGuildById(member.guild());
				Member cached = guild == null ? null : guild.getMemberById(member.id());
				yield cached == null ? null : cached.getEffectiveName();
			}
			case GuildRef guild -> {
				Guild cached = jda().getGuildById(guild.id());
				yield cached == null ? null : cached.getName();
			}
			case ChannelRef channel -> {
				MessageChannel cached = jda().getChannelById(MessageChannel.class, channel.id());
				yield cached == null ? null : cached.getName();
			}
			case CategoryRef category -> {
				Category cached = jda().getCategoryById(category.id());
				yield cached == null ? null : cached.getName();
			}
			case RoleRef role -> {
				Role cached = jda().getRoleById(role.id());
				yield cached == null ? null : cached.getName();
			}
			case MessageRef message -> null;
		};
	}

	@Override
	public @Nullable Boolean cachedBot(UserRef user) {
		User cached = jda().getUserById(user.id());
		return cached == null ? null : cached.isBot();
	}

	@Override
	public long cachedGatewayPing() {
		return jda().getGatewayPing();
	}

	@Override
	public @Nullable String checkCommand(CommandSpec spec) {
		return JdaSpecs.problem(spec);
	}

	@Override
	public CompletionStage<MessageRef> send(ChannelRef channel, String text) {
		MessageChannel found = jda().getChannelById(MessageChannel.class, channel.id());
		if (found == null)
			return unknownChannel();
		return found.sendMessage(text).submit().thenApply(JdaRefs::message);
	}

	@Override
	public CompletionStage<MessageRef> reply(MessageRef message, String text) {
		MessageChannel found = jda().getChannelById(MessageChannel.class, message.channel());
		if (found == null)
			return unknownChannel();
		return found.sendMessage(text).setMessageReference(message.id()).submit().thenApply(JdaRefs::message);
	}

	@Override
	public CompletionStage<MessageRef> edit(MessageRef message, String text) {
		InteractionHook hook = JdaResponder.hookFor(message.id());
		if (hook != null)
			return hook.editMessageById(message.id(), text).submit().thenApply(JdaRefs::message);
		MessageChannel found = jda().getChannelById(MessageChannel.class, message.channel());
		if (found == null)
			return unknownChannel();
		return found.editMessageById(message.id(), text).submit().thenApply(JdaRefs::message);
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(CategoryRef category, String name) {
		Category found = jda().getCategoryById(category.id());
		if (found == null)
			return unknownChannel();
		return found.createTextChannel(name).submit().thenApply(JdaRefs::channel);
	}

	@Override
	public CompletionStage<ChannelRef> createTextChannel(GuildRef guild, String name) {
		Guild found = jda().getGuildById(guild.id());
		if (found == null)
			return failed(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
		return found.createTextChannel(name).submit().thenApply(JdaRefs::channel);
	}

	@Override
	public CompletionStage<Long> gatewayPing() {
		return CompletableFuture.completedFuture(jda().getGatewayPing());
	}

	@Override
	public CompletionStage<Object> fetch(DiscordRef ref, FetchProperty property) {
		return switch (ref) {
			case UserRef user -> map(jda().retrieveUserById(user.id()).submit(), found -> switch (property) {
				case NAME -> found.getName();
				case BOT -> found.isBot();
				case AVATAR_URL -> found.getEffectiveAvatarUrl();
				default -> throw unsupported(ref, property);
			});
			case MemberRef member -> {
				Guild guild = jda().getGuildById(member.guild());
				if (guild == null)
					yield failed(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
				yield map(guild.retrieveMemberById(member.id()).submit(), found -> switch (property) {
					case NAME -> found.getEffectiveName();
					case BOT -> found.getUser().isBot();
					case AVATAR_URL -> found.getEffectiveAvatarUrl();
					case ROLES -> {
						List<RoleRef> roles = new ArrayList<>();
						for (Role role : found.getRoles())
							roles.add(JdaRefs.role(role));
						yield roles;
					}
					default -> throw unsupported(ref, property);
				});
			}
			case GuildRef guild -> {
				Guild found = jda().getGuildById(guild.id());
				if (found == null)
					yield failed(DiscordError.Type.MISSING_ACCESS, "Missing Access", 50001);
				yield switch (property) {
					case NAME -> CompletableFuture.completedFuture((Object) found.getName());
					case AVATAR_URL -> CompletableFuture.completedFuture((Object) found.getIconUrl());
					default -> CompletableFuture.failedFuture(unsupported(ref, property));
				};
			}
			case ChannelRef channel -> {
				MessageChannel found = jda().getChannelById(MessageChannel.class, channel.id());
				if (found == null)
					yield unknownChannel();
				yield property == FetchProperty.NAME
					? CompletableFuture.completedFuture((Object) found.getName())
					: CompletableFuture.failedFuture(unsupported(ref, property));
			}
			case CategoryRef category -> {
				Category found = jda().getCategoryById(category.id());
				if (found == null)
					yield unknownChannel();
				yield property == FetchProperty.NAME
					? CompletableFuture.completedFuture((Object) found.getName())
					: CompletableFuture.failedFuture(unsupported(ref, property));
			}
			case RoleRef role -> {
				Role found = jda().getRoleById(role.id());
				if (found == null)
					yield failed(DiscordError.Type.UNKNOWN_ROLE, "Unknown Role", 10011);
				yield property == FetchProperty.NAME
					? CompletableFuture.completedFuture((Object) found.getName())
					: CompletableFuture.failedFuture(unsupported(ref, property));
			}
			case MessageRef message -> {
				MessageChannel found = jda().getChannelById(MessageChannel.class, message.channel());
				if (found == null)
					yield unknownChannel();
				yield map(found.retrieveMessageById(message.id()).submit(), fetched -> switch (property) {
					case CONTENT -> fetched.getContentRaw();
					case NAME -> fetched.getContentDisplay();
					default -> throw unsupported(ref, property);
				});
			}
		};
	}

	private static <T, R> CompletionStage<R> map(CompletionStage<T> stage, Function<T, Object> mapper) {
		@SuppressWarnings("unchecked")
		CompletionStage<R> mapped = (CompletionStage<R>) stage.thenApply(mapper);
		return mapped;
	}

	private static IllegalArgumentException unsupported(DiscordRef ref, FetchProperty property) {
		String kind = ref.getClass().getSimpleName().replace("Ref", "").toLowerCase(java.util.Locale.ROOT);
		return new IllegalArgumentException("A " + kind + " has no " + property.name().toLowerCase(java.util.Locale.ROOT)
			.replace('_', ' '));
	}

	private static <T> CompletionStage<T> unknownChannel() {
		return failed(DiscordError.Type.UNKNOWN_CHANNEL, "Unknown Channel", 10003);
	}

	private static <T> CompletionStage<T> failed(DiscordError.Type type, String message, int code) {
		return CompletableFuture.failedFuture(new DiscordException(new DiscordError(type, message, null, code)));
	}
}
