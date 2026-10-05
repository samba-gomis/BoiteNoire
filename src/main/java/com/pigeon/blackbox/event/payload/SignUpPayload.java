package com.pigeon.blackbox.event.payload;

import java.util.Objects;

import com.pigeon.blackbox.user.Plan;

public record SignUpPayload(
		AcquisitionChannel acquisitionChannel,
		/* Reference to users._id, set only when acquisitionChannel is REFERRAL */
		String referrerUserId,
		Plan initialPlan) implements EventPayload {

	public SignUpPayload {
		Objects.requireNonNull(acquisitionChannel, "acquisitionChannel is required");
		Objects.requireNonNull(initialPlan, "initialPlan is required");
		if ((acquisitionChannel == AcquisitionChannel.REFERRAL) != (referrerUserId != null)) {
			throw new IllegalArgumentException(
					"referrerUserId must be set if and only if acquisitionChannel is REFERRAL");
		}
	}

	public enum AcquisitionChannel {
		ORGANIC,
		ADS,
		REFERRAL,
		PARTNER
	}
}
