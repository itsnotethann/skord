package io.github.itsnotethann.skord.testbot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.itsnotethann.skord.command.CommandSync;
import io.github.itsnotethann.skord.interaction.Responder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import org.jetbrains.annotations.Nullable;

/**
 * Discord, as far as skord's ports reach. Interaction responses are recorded against the script that fired the
 * interaction; plain requests are futures a script completes or fails by name; command updates are counted.
 */
final class FakeDiscord {

	private static final Map<String, CompletableFuture<Object>> REQUESTS = new ConcurrentHashMap<>();
	private static final AtomicInteger COMMAND_REQUESTS = new AtomicInteger();
	private static volatile List<String> synced = List.of();

	private FakeDiscord() {}

	/**
	 * The request called {@code id}. Whichever side comes first creates it, so a script may complete a
	 * request before the trigger that makes it has got there.
	 */
	static CompletableFuture<Object> request(String id) {
		return REQUESTS.computeIfAbsent(id, key -> new CompletableFuture<>());
	}

	/** Stands in for Discord's error responses, whose message is what 'last discord error' shows. */
	static final class FakeError extends RuntimeException {
		FakeError(String message) {
			super(message);
		}
	}

	/** What JDA throws when its own check finds a permission missing: it names the permission. */
	static final class FakePermissionError extends PermissionException {
		FakePermissionError(Permission permission) {
			super(permission, "Cannot perform action due to a lack of Permission. Missing permission: " + permission);
		}
	}

	/** Discord's own error response with this code, as JDA reports it. */
	static ErrorResponseException response(int code) {
		return ErrorResponseException.create(ErrorResponse.fromCode(code), new Response(new Exception("fake"), Set.of()));
	}

	static int commandRequests() {
		return COMMAND_REQUESTS.get();
	}

	static List<String> syncedCommands() {
		return synced;
	}

	static CommandSync.Sink commandSink() {
		return (guildId, commands) -> {
			COMMAND_REQUESTS.incrementAndGet();
			synced = commands.stream().map(CommandData::getName).sorted().toList();
			return CompletableFuture.completedFuture(null);
		};
	}

	/** Answers every request at once, and records it under {@code script}. */
	static Responder responder(String script) {
		return new Responder() {
			@Override
			public CompletionStage<?> deferReply(boolean ephemeral) {
				return record(ephemeral ? "defer ephemerally" : "defer");
			}

			@Override
			public CompletionStage<@Nullable Message> reply(String text, boolean ephemeral, boolean wantMessage) {
				return record("reply" + (ephemeral ? " ephemerally" : "") + ": " + text);
			}

			@Override
			public CompletionStage<@Nullable Message> editOriginal(String text, boolean wantMessage) {
				return record("edit original: " + text);
			}

			@Override
			public CompletionStage<@Nullable Message> editMessage(String messageId, String text) {
				return record("edit message " + messageId + ": " + text);
			}

			@Override
			public CompletionStage<?> deleteOriginal() {
				return record("delete original");
			}

			@Override
			public CompletionStage<@Nullable Message> followUp(String text, boolean ephemeral, boolean wantMessage) {
				return record("follow-up" + (ephemeral ? " ephemerally" : "") + ": " + text);
			}

			private <T> CompletionStage<@Nullable T> record(String request) {
				TestBot.recorder().mark(script, request);
				return CompletableFuture.completedFuture(null);
			}
		};
	}
}
