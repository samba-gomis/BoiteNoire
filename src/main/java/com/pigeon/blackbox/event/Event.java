package com.pigeon.blackbox.event;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.pigeon.blackbox.event.payload.EventPayload;

@Document("events")
public record Event(
		@Id String id,
		EventType type,
		/* When the event happened, stored as a UTC date */
		Instant timestamp,
		/* Reference to users._id */
		String userId,
		/* null outside an interactive session: API key call or notification */
		String sessionId,
		Platform platform,
		/* Embedded: its structure depends on type */
		EventPayload payload) {

	public Event {
		Objects.requireNonNull(type, "type is required");
		Objects.requireNonNull(timestamp, "timestamp is required");
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(platform, "platform is required");
		Objects.requireNonNull(payload, "payload is required");

		if (!type.accepts(payload)) {
			throw new IllegalArgumentException(type + " requires a " + type.payloadType().getSimpleName()
					+ ", got: " + payload.getClass().getSimpleName());
		}

		boolean interactive = platform != Platform.API && platform != Platform.SYSTEM;
		if (interactive != (sessionId != null)) {
			throw new IllegalArgumentException("sessionId must be set if and only if the platform is interactive, got: "
					+ platform + " with sessionId " + sessionId);
		}
		if (type == EventType.API_CALLED && platform != Platform.API) {
			throw new IllegalArgumentException("API_CALLED events come from the API platform, got: " + platform);
		}
		if ((type == EventType.NOTIFICATION_SENT) != (platform == Platform.SYSTEM)) {
			throw new IllegalArgumentException("the SYSTEM platform is reserved to NOTIFICATION_SENT events, got: "
					+ type + " on " + platform);
		}
	}

	/* New event, before insertion: MongoDB generates the id */
	public static Event of(EventType type, Instant timestamp, String userId, String sessionId, Platform platform,
			EventPayload payload) {
		return new Event(null, type, timestamp, userId, sessionId, platform, payload);
	}
}
