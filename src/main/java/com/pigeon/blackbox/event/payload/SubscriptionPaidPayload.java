package com.pigeon.blackbox.event.payload;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import com.pigeon.blackbox.user.Plan;

public record SubscriptionPaidPayload(
		/* Stored as Decimal128: never a double for money, never a string */
		@Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
		/* ISO 4217 code, e.g. EUR */
		String currency,
		/* Plan actually paid that day: a historical fact, unlike users.plan */
		Plan plan,
		BillingPeriod billingPeriod,
		int seats,
		PaymentMethod paymentMethod,
		String invoiceId) implements EventPayload {

	public SubscriptionPaidPayload {
		Objects.requireNonNull(plan, "plan is required");
		Objects.requireNonNull(billingPeriod, "billingPeriod is required");
		Objects.requireNonNull(paymentMethod, "paymentMethod is required");
		Objects.requireNonNull(invoiceId, "invoiceId is required");

		if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
			throw new IllegalArgumentException("amount must be positive with at most 2 decimals, got: " + amount);
		}
		if (currency == null || !currency.matches("[A-Z]{3}")) {
			throw new IllegalArgumentException("currency must be an ISO 4217 code, got: " + currency);
		}
		if (plan == Plan.FREE) {
			throw new IllegalArgumentException("a subscription payment cannot be for the FREE plan");
		}
		if (seats < 1) {
			throw new IllegalArgumentException("seats must be at least 1, got: " + seats);
		}
	}

	public enum BillingPeriod {
		MONTHLY,
		YEARLY
	}

	public enum PaymentMethod {
		CARD,
		SEPA,
		PAYPAL
	}
}
