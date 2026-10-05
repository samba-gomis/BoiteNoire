package com.pigeon.blackbox.event.payload;

import java.util.List;
import java.util.Objects;

public record ApplicationErrorPayload(
		ErrorType errorType,
		Severity severity,
		/* Pigeon service that raised the error, e.g. messaging-service */
		String service,
		/* Route template of the failing request, e.g. /api/v1/conversations/{conversationId}/messages */
		String endpoint,
		String message,
		/* Embedded: truncated to the top frames, never read without its error */
		List<StackFrame> stackTrace) implements EventPayload {

	public static final int MAX_STACK_FRAMES = 20;

	public ApplicationErrorPayload {
		Objects.requireNonNull(errorType, "errorType is required");
		Objects.requireNonNull(severity, "severity is required");
		Objects.requireNonNull(service, "service is required");
		Objects.requireNonNull(message, "message is required");
		if (endpoint == null || !endpoint.startsWith("/")) {
			throw new IllegalArgumentException("endpoint must be a route template starting with /, got: " + endpoint);
		}

		if (stackTrace == null) {
			stackTrace = List.of();
		}
		else if (stackTrace.size() > MAX_STACK_FRAMES) {
			stackTrace = List.copyOf(stackTrace.subList(0, MAX_STACK_FRAMES));
		}
		else {
			stackTrace = List.copyOf(stackTrace);
		}
	}

	public enum ErrorType {
		DATABASE_TIMEOUT,
		UPSTREAM_UNAVAILABLE,
		NULL_REFERENCE,
		VALIDATION_FAILED,
		RATE_LIMIT_EXCEEDED
	}

	public enum Severity {
		WARNING,
		ERROR,
		CRITICAL
	}

	public record StackFrame(String className, String methodName, int lineNumber) {

		public StackFrame {
			Objects.requireNonNull(className, "className is required");
			Objects.requireNonNull(methodName, "methodName is required");
		}
	}
}
