package com.pigeon.blackbox.analytics;

import java.time.LocalDate;
import java.util.List;

import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Errors of one day (Paris time), broken down by type")
public record DailyErrors(
		@Schema(example = "2025-06-03") LocalDate day,
		@Schema(example = "212") long total,
		@Schema(description = "Most frequent type first") List<ErrorTypeCount> byType) {

	public record ErrorTypeCount(ErrorType errorType, @Schema(example = "117") long count) {
	}
}
