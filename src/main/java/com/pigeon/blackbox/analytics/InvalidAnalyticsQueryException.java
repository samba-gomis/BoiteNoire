package com.pigeon.blackbox.analytics;

/* A well-formed parameter that makes no sense, e.g. a period ending before it starts: answered with a 400 */
public class InvalidAnalyticsQueryException extends RuntimeException {

	public InvalidAnalyticsQueryException(String message) {
		super(message);
	}
}
