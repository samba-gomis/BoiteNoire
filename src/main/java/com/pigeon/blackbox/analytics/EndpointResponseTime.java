package com.pigeon.blackbox.analytics;

import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response times of one route of the public API")
public record EndpointResponseTime(
		HttpMethod method,
		@Schema(example = "/api/v1/search") String endpoint,
		@Schema(example = "4570") long calls,
		@Schema(description = "Average response time, in milliseconds", example = "431.2") double averageMs,
		@Schema(description = "95th percentile: 95% of the calls answered faster, in milliseconds", example = "862.0")
		double p95Ms) {
}
