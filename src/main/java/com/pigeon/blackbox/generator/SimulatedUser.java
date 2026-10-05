package com.pigeon.blackbox.generator;

import java.time.Instant;
import java.time.LocalDate;

import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload.BillingPeriod;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload.PaymentMethod;
import com.pigeon.blackbox.user.Plan;
import com.pigeon.blackbox.user.User;

/* A user as seen by the simulation: its profile plus what decides its behaviour */
record SimulatedUser(
		String id,
		String displayName,
		String company,
		String country,
		String city,
		Instant signedUpAt,
		/* Zipf share of the daily activity: a few users weigh a lot, most weigh little */
		double activityWeight,
		Platform preferredPlatform,
		/* null: the user never calls the public API */
		String apiKeyId,
		/* null: the user never sends a message (drops out of the funnel) */
		Instant firstMessageAt,
		/* null: the user stays on the FREE plan */
		Subscription subscription) {

	record Subscription(Plan plan, BillingPeriod billingPeriod, int seats, PaymentMethod paymentMethod,
			Instant startAt) {
	}

	Plan currentPlan() {
		return subscription == null ? Plan.FREE : subscription.plan();
	}

	User toUser() {
		return new User(id, displayName, company, country, currentPlan(), signedUpAt);
	}

	boolean hasApiKey() {
		return apiKeyId != null;
	}

	/* Messages after the first one only: the first one belongs to the funnel */
	boolean canSendMessagesAt(Instant instant) {
		return firstMessageAt != null && instant.isAfter(firstMessageAt);
	}

	/* Events of the same user, on the same day and platform, share a session */
	String sessionIdAt(Instant instant, Platform platform) {
		LocalDate day = instant.atZone(ActivityCalendar.ZONE).toLocalDate();
		return "ses_%08x".formatted((id + '|' + day + '|' + platform).hashCode());
	}
}
