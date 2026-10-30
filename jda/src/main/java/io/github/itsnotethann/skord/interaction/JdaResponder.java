package io.github.itsnotethann.skord.interaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.itsnotethann.skord.discord.MessageRef;
import io.github.itsnotethann.skord.discord.Responder;
import io.github.itsnotethann.skord.jda.JdaRefs;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.jetbrains.annotations.Nullable;

/** Answers an interaction through JDA. */
public final class JdaResponder implements Responder {

	// Messages sent through an interaction, by ID, with the hook that sent them. An ephemeral message can only
	// be edited through its hook, so 'edit' looks here first. Hooks expire after 15 minutes; a small LRU is
	// plenty.
	private static final Map<Long, InteractionHook> HOOKED = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, InteractionHook> eldest) {
			return size() > 512;
		}
	};

	private final IReplyCallback callback;

	public JdaResponder(IReplyCallback callback) {
		this.callback = callback;
	}

	/** The live hook that can edit the message with this ID, if it was sent through an interaction. */
	public static @Nullable InteractionHook hookFor(long messageId) {
		InteractionHook hook;
		synchronized (HOOKED) {
			hook = HOOKED.get(messageId);
		}
		return hook == null || hook.isExpired() ? null : hook;
	}

	@Override
	public CompletionStage<?> deferReply(boolean ephemeral) {
		return callback.deferReply(ephemeral).submit();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> reply(String text, boolean ephemeral, boolean wantMessage) {
		return callback.reply(text).setEphemeral(ephemeral).submit().thenCompose(hook -> {
			if (!wantMessage)
				return CompletableFuture.completedFuture(null);
			// Discord usually returns the message with the response; fetch it only when it didn't.
			if (hook.hasCallbackResponse() && hook.getCallbackResponse().getMessage() != null)
				return CompletableFuture.completedFuture(track(hook.getCallbackResponse().getMessage()));
			return hook.retrieveOriginal().submit().thenApply(this::track);
		});
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editOriginal(String text, boolean wantMessage) {
		return callback.getHook().editOriginal(text).submit().thenApply(this::track);
	}

	@Override
	public CompletionStage<@Nullable MessageRef> editMessage(String messageId, String text) {
		return callback.getHook().editMessageById(messageId, text).submit().thenApply(this::track);
	}

	@Override
	public CompletionStage<?> deleteOriginal() {
		return callback.getHook().deleteOriginal().submit();
	}

	@Override
	public CompletionStage<@Nullable MessageRef> followUp(String text, boolean ephemeral, boolean wantMessage) {
		return callback.getHook().sendMessage(text).setEphemeral(ephemeral).submit().thenApply(this::track);
	}

	private MessageRef track(Message message) {
		synchronized (HOOKED) {
			HOOKED.put(message.getIdLong(), callback.getHook());
		}
		return JdaRefs.message(message);
	}
}
