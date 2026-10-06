package com.pigeon.blackbox.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.ApiCallPayload;
import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.Severity;
import com.pigeon.blackbox.event.payload.EventPayload;
import com.pigeon.blackbox.event.payload.LoginPayload;
import com.pigeon.blackbox.event.payload.MessageSentPayload;
import com.pigeon.blackbox.event.payload.NotificationSentPayload;
import com.pigeon.blackbox.event.payload.SignUpPayload;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload;
import com.pigeon.blackbox.user.Plan;
import com.pigeon.blackbox.user.User;

/* Minimal valid events, so that each test only states what matters: who, when, which type */
final class TestData {

	private TestData() {
	}

	static User user(String id, String displayName) {
		return new User(id, displayName, "Atelier Nord", "FR", Plan.FREE, Instant.parse("2025-01-01T00:00:00Z"));
	}

	static Event signUp(String userId, String at) {
		return interactive(EventType.USER_SIGNED_UP, userId, at,
				new SignUpPayload(SignUpPayload.AcquisitionChannel.ORGANIC, null, Plan.FREE));
	}

	static Event login(String userId, String at) {
		return interactive(EventType.USER_LOGGED_IN, userId, at, new LoginPayload(LoginPayload.AuthMethod.PASSWORD,
				true, false, "203.0.113.1", "test-agent", new LoginPayload.Geo("FR", "Paris")));
	}

	static Event message(String userId, String at) {
		return interactive(EventType.MESSAGE_SENT, userId, at, new MessageSentPayload("conv_1",
				MessageSentPayload.ConversationType.DIRECT, 1, 42, List.of()));
	}

	static Event payment(String userId, String at) {
		return interactive(EventType.SUBSCRIPTION_PAID, userId, at, new SubscriptionPaidPayload(new BigDecimal("9.00"),
				"EUR", Plan.PRO, SubscriptionPaidPayload.BillingPeriod.MONTHLY, 1,
				SubscriptionPaidPayload.PaymentMethod.CARD, "inv_test"));
	}

	static Event error(String userId, String at, ErrorType errorType) {
		return interactive(EventType.APPLICATION_ERROR, userId, at, new ApplicationErrorPayload(errorType,
				Severity.ERROR, "test-service", "/api/v1/test", "test error", List.of()));
	}

	static Event notification(String userId, String at) {
		return Event.of(EventType.NOTIFICATION_SENT, Instant.parse(at), userId, null, Platform.SYSTEM,
				new NotificationSentPayload(NotificationSentPayload.Channel.EMAIL, "weekly_digest", true,
						List.of(new NotificationSentPayload.DeliveryAttempt(Instant.parse(at),
								NotificationSentPayload.DeliveryOutcome.DELIVERED))));
	}

	static Event apiCall(String at, HttpMethod method, String endpoint, int responseTimeMs) {
		return Event.of(EventType.API_CALLED, Instant.parse(at), "usr_api", null, Platform.API,
				new ApiCallPayload(method, endpoint, 200, responseTimeMs, "key_test"));
	}

	private static Event interactive(EventType type, String userId, String at, EventPayload payload) {
		return Event.of(type, Instant.parse(at), userId, "ses_test", Platform.WEB, payload);
	}
}
