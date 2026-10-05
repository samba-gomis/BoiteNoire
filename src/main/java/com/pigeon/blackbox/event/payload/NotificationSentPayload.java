package com.pigeon.blackbox.event.payload;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record NotificationSentPayload(
		Channel channel,
		/* Notification template, e.g. weekly_digest */
		String template,
		/* Redundant with the last attempt, kept to filter on delivery without reading the array */
		boolean delivered,
		/* Embedded: the sender gives up after 3 attempts, never read without its notification */
		List<DeliveryAttempt> attempts) implements EventPayload {

	public static final int MAX_ATTEMPTS = 3;

	public NotificationSentPayload {
		Objects.requireNonNull(channel, "channel is required");
		Objects.requireNonNull(template, "template is required");
		if (attempts == null || attempts.isEmpty() || attempts.size() > MAX_ATTEMPTS) {
			throw new IllegalArgumentException("a notification has between 1 and " + MAX_ATTEMPTS + " attempts");
		}
		attempts = List.copyOf(attempts);

		for (int i = 1; i < attempts.size(); i++) {
			DeliveryAttempt previous = attempts.get(i - 1);
			if (previous.outcome() == DeliveryOutcome.DELIVERED) {
				throw new IllegalArgumentException("no attempt can follow a successful delivery");
			}
			if (attempts.get(i).attemptedAt().isBefore(previous.attemptedAt())) {
				throw new IllegalArgumentException("attempts must be in chronological order");
			}
		}
		if (delivered != (attempts.getLast().outcome() == DeliveryOutcome.DELIVERED)) {
			throw new IllegalArgumentException("delivered must match the outcome of the last attempt");
		}
	}

	public enum Channel {
		EMAIL,
		PUSH,
		SMS
	}

	public enum DeliveryOutcome {
		DELIVERED,
		BOUNCED,
		TIMEOUT
	}

	public record DeliveryAttempt(Instant attemptedAt, DeliveryOutcome outcome) {

		public DeliveryAttempt {
			Objects.requireNonNull(attemptedAt, "attemptedAt is required");
			Objects.requireNonNull(outcome, "outcome is required");
		}
	}
}
