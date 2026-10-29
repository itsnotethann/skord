package io.github.itsnotethann.skord.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

/** The {@code boostStart} shared function: which guilds this Discord user is boosting, and since when. */
final class BoostLookup {

	private BoostLookup() {}

	static CompletionStage<JsonElement> answer(JDA jda, String userId) {
		List<CompletableFuture<JsonObject>> lookups = new ArrayList<>();
		for (Guild guild : jda.getGuilds()) {
			lookups.add(guild.retrieveMemberById(userId).submit().handle((member, error) -> {
				if (error != null) {
					if (error instanceof ErrorResponseException || error.getCause() instanceof ErrorResponseException)
						return null;
					throw new IllegalStateException(error);
				}
				if (member.getTimeBoosted() == null)
					return null;
				JsonObject entry = new JsonObject();
				entry.addProperty("guildId", guild.getId());
				entry.addProperty("startEpoch", member.getTimeBoosted().toEpochSecond());
				return entry;
			}));
		}
		return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
			JsonArray out = new JsonArray();
			for (CompletableFuture<JsonObject> lookup : lookups) {
				JsonObject entry = lookup.join();
				if (entry != null)
					out.add(entry);
			}
			return out;
		});
	}
}
