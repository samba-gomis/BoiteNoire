package com.pigeon.blackbox.user;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record User(
		/* Readable identifier produced by the generator, e.g. usr_000042: stored as a string, not an ObjectId */
		@Id String id,
		String displayName,
		String company,
		/* ISO 3166-1 alpha-2 code, e.g. FR */
		String country,
		/* Current plan: changes over time, unlike SUBSCRIPTION_PAID.payload.plan */
		Plan plan,
		Instant signedUpAt) {

	public User {
		Objects.requireNonNull(id, "id is required");
		Objects.requireNonNull(displayName, "displayName is required");
		Objects.requireNonNull(company, "company is required");
		Objects.requireNonNull(plan, "plan is required");
		Objects.requireNonNull(signedUpAt, "signedUpAt is required");
		if (country == null || !country.matches("[A-Z]{2}")) {
			throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code, got: " + country);
		}
	}
}
