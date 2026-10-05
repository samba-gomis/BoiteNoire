package com.pigeon.blackbox.event.payload;

import java.util.Objects;

public record LoginPayload(
		AuthMethod authMethod,
		boolean success,
		boolean mfaUsed,
		String ip,
		String userAgent,
		/* Embedded: small, fixed size, always read with the login */
		Geo geo) implements EventPayload {

	public LoginPayload {
		Objects.requireNonNull(authMethod, "authMethod is required");
		Objects.requireNonNull(ip, "ip is required");
		Objects.requireNonNull(userAgent, "userAgent is required");
		Objects.requireNonNull(geo, "geo is required");
	}

	public enum AuthMethod {
		PASSWORD,
		GOOGLE_SSO,
		MICROSOFT_SSO,
		MAGIC_LINK
	}

	public record Geo(String country, String city) {

		public Geo {
			Objects.requireNonNull(city, "city is required");
			if (country == null || !country.matches("[A-Z]{2}")) {
				throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code, got: " + country);
			}
		}
	}
}
