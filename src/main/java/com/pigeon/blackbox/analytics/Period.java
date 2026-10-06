package com.pigeon.blackbox.analytics;

import java.time.Instant;

/* Time window [from, to): from included, to excluded. A null bound leaves that side open. */
public record Period(Instant from, Instant to) {

	public Period {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new InvalidAnalyticsQueryException("from must be before to, got from=" + from + " and to=" + to);
		}
	}

	public static Period unbounded() {
		return new Period(null, null);
	}
}
