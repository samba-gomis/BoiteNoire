package com.pigeon.blackbox.analytics;

import com.pigeon.blackbox.event.EventType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One step of the funnel and the users who reached it, steps taken in order")
public record FunnelStep(
		@Schema(description = "Position of the step, starting at 1", example = "2") int position,
		@Schema(example = "MESSAGE_SENT") EventType step,
		@Schema(description = "Users who reached this step after the previous ones", example = "2128") long users,
		@Schema(description = "Share of the users of the previous step, in percent", example = "70.9")
		double percentOfPrevious,
		@Schema(description = "Share of the users of the first step, in percent", example = "70.9")
		double percentOfFirst) {
}
