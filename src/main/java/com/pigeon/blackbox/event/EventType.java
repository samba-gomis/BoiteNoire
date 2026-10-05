package com.pigeon.blackbox.event;

import java.util.Arrays;
import java.util.List;

import com.pigeon.blackbox.event.payload.ApiCallPayload;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload;
import com.pigeon.blackbox.event.payload.EventPayload;
import com.pigeon.blackbox.event.payload.LoginPayload;
import com.pigeon.blackbox.event.payload.MessageSentPayload;
import com.pigeon.blackbox.event.payload.NotificationSentPayload;
import com.pigeon.blackbox.event.payload.SignUpPayload;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload;

public enum EventType {

	USER_SIGNED_UP(SignUpPayload.class, true),
	USER_LOGGED_IN(LoginPayload.class, true),
	MESSAGE_SENT(MessageSentPayload.class, true),
	SUBSCRIPTION_PAID(SubscriptionPaidPayload.class, true),
	API_CALLED(ApiCallPayload.class, true),

	/* Consequence of a user request, not an action of the user */
	APPLICATION_ERROR(ApplicationErrorPayload.class, false),

	/* Received by the user, not done by the user */
	NOTIFICATION_SENT(NotificationSentPayload.class, false);

	private final Class<? extends EventPayload> payloadType;

	/* Counts as activity in the "most active users" analysis */
	private final boolean userInitiated;

	EventType(Class<? extends EventPayload> payloadType, boolean userInitiated) {
		this.payloadType = payloadType;
		this.userInitiated = userInitiated;
	}

	public Class<? extends EventPayload> payloadType() {
		return payloadType;
	}

	public boolean userInitiated() {
		return userInitiated;
	}

	public boolean accepts(EventPayload payload) {
		return payloadType.isInstance(payload);
	}

	public static List<EventType> userInitiatedTypes() {
		return Arrays.stream(values()).filter(type -> type.userInitiated).toList();
	}
}
