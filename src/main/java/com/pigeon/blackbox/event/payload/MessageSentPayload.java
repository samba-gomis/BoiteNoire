package com.pigeon.blackbox.event.payload;

import java.util.List;
import java.util.Objects;

public record MessageSentPayload(
		/* Opaque identifier from Pigeon's messaging system, not resolved by this service */
		String conversationId,
		ConversationType conversationType,
		int recipientCount,
		/* Length only: message content is never logged */
		int contentLength,
		/* Embedded: bounded list, metadata only, never read without its message */
		List<Attachment> attachments) implements EventPayload {

	public static final int MAX_ATTACHMENTS = 10;

	public MessageSentPayload {
		Objects.requireNonNull(conversationId, "conversationId is required");
		Objects.requireNonNull(conversationType, "conversationType is required");
		attachments = attachments == null ? List.of() : List.copyOf(attachments);

		if (recipientCount < 1) {
			throw new IllegalArgumentException("recipientCount must be at least 1, got: " + recipientCount);
		}
		if (conversationType == ConversationType.DIRECT && recipientCount != 1) {
			throw new IllegalArgumentException("a DIRECT message has exactly 1 recipient, got: " + recipientCount);
		}
		if (contentLength < 0) {
			throw new IllegalArgumentException("contentLength cannot be negative, got: " + contentLength);
		}
		if (contentLength == 0 && attachments.isEmpty()) {
			throw new IllegalArgumentException("a message needs text or at least one attachment");
		}
		if (attachments.size() > MAX_ATTACHMENTS) {
			throw new IllegalArgumentException(
					"at most " + MAX_ATTACHMENTS + " attachments per message, got: " + attachments.size());
		}
	}

	public enum ConversationType {
		DIRECT,
		GROUP,
		CHANNEL
	}

	public record Attachment(String mimeType, long sizeBytes) {

		public Attachment {
			if (mimeType == null || !mimeType.contains("/")) {
				throw new IllegalArgumentException("mimeType must look like type/subtype, got: " + mimeType);
			}
			if (sizeBytes <= 0) {
				throw new IllegalArgumentException("sizeBytes must be positive, got: " + sizeBytes);
			}
		}
	}
}
