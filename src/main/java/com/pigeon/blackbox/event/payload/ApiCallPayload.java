package com.pigeon.blackbox.event.payload;

import java.util.Objects;
import java.util.regex.Pattern;

public record ApiCallPayload(
		HttpMethod method,
		/* Route template, not the real URL: /api/v1/channels/{channelId}/messages, not /api/v1/channels/8812/messages */
		String endpoint,
		int statusCode,
		int responseTimeMs,
		/* Opaque identifier of the API key, not resolved by this service */
		String apiKeyId) implements EventPayload {

	/* A path segment made only of digits is a raw identifier, e.g. /channels/8812 */
	private static final Pattern RAW_ID_SEGMENT = Pattern.compile("/\\d+(/|$)");

	public ApiCallPayload {
		Objects.requireNonNull(method, "method is required");
		Objects.requireNonNull(apiKeyId, "apiKeyId is required");

		if (endpoint == null || !endpoint.startsWith("/")) {
			throw new IllegalArgumentException("endpoint must be a route template starting with /, got: " + endpoint);
		}
		if (RAW_ID_SEGMENT.matcher(endpoint).find()) {
			throw new IllegalArgumentException("endpoint must be a route template, not a real URL, got: " + endpoint);
		}
		if (statusCode < 100 || statusCode > 599) {
			throw new IllegalArgumentException("statusCode must be a valid HTTP status, got: " + statusCode);
		}
		if (responseTimeMs < 0) {
			throw new IllegalArgumentException("responseTimeMs cannot be negative, got: " + responseTimeMs);
		}
	}

	public enum HttpMethod {
		GET,
		POST,
		PUT,
		PATCH,
		DELETE
	}
}
