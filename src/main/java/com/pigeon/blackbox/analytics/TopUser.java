package com.pigeon.blackbox.analytics;

import com.pigeon.blackbox.user.Plan;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A user and the number of events they initiated over the period")
public record TopUser(
		@Schema(example = "usr_000045") String userId,
		@Schema(description = "null when the user has no profile in the users collection", example = "Léa Martin")
		String displayName,
		@Schema(example = "Atelier Nord") String company,
		@Schema(description = "Current plan of the user") Plan plan,
		@Schema(description = "Events initiated by the user: signups, logins, messages, payments and API calls",
				example = "4210")
		long eventCount) {
}
