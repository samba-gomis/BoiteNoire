package com.pigeon.blackbox.analytics;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pigeon.blackbox.event.EventType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics", description = "The four analyses of Pigeon's events, each computed by a MongoDB aggregation pipeline")
@ApiResponse(responseCode = "400", description = "Missing or invalid parameter",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AnalyticsController {

	private static final String FROM_DESCRIPTION = "Start of the period, included (ISO-8601 instant)";
	private static final String TO_DESCRIPTION = "End of the period, excluded (ISO-8601 instant)";

	private final AnalyticsService analyticsService;

	public AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@GetMapping("/top-users")
	@Operation(summary = "Most active users over a period",
			description = """
					Counts the events each user initiated between from and to (signups, logins, messages, \
					payments and API calls), then returns the most active users with their profile. \
					Notifications and errors are not counted: they happen to the user rather than \
					being actions of the user.""")
	@ApiResponse(responseCode = "200", description = "Users sorted by decreasing activity")
	public List<TopUser> topActiveUsers(
			@Parameter(description = FROM_DESCRIPTION, example = "2025-01-01T00:00:00Z") @RequestParam Instant from,
			@Parameter(description = TO_DESCRIPTION, example = "2026-01-01T00:00:00Z") @RequestParam Instant to,
			@Parameter(description = "Number of users to return") @RequestParam(defaultValue = "10") @Min(1)
			@Max(AnalyticsService.MAX_LIMIT) int limit) {
		return analyticsService.topActiveUsers(new Period(from, to), limit);
	}

	@GetMapping("/errors")
	@Operation(summary = "Errors by type and by day over a period",
			description = """
					Counts the APPLICATION_ERROR events of each day between from and to, broken down by \
					error type. Days are cut at midnight, Paris time; days without errors are omitted.""")
	@ApiResponse(responseCode = "200", description = "One entry per day with errors, in chronological order")
	public List<DailyErrors> errorsByTypeAndDay(
			@Parameter(description = FROM_DESCRIPTION, example = "2025-06-01T00:00:00Z") @RequestParam Instant from,
			@Parameter(description = TO_DESCRIPTION, example = "2025-07-01T00:00:00Z") @RequestParam Instant to) {
		return analyticsService.errorsByTypeAndDay(new Period(from, to));
	}

	@GetMapping("/response-times")
	@Operation(summary = "Response time by endpoint: average and 95th percentile",
			description = """
					Groups the API_CALLED events by HTTP method and route, then computes the number of calls, \
					the average response time and the 95th percentile (estimated by MongoDB with $percentile). \
					Without from and to, the whole history is used.""")
	@ApiResponse(responseCode = "200", description = "One entry per route, slowest 95th percentile first")
	public List<EndpointResponseTime> responseTimesByEndpoint(
			@Parameter(description = FROM_DESCRIPTION + ", optional") @RequestParam(required = false) Instant from,
			@Parameter(description = TO_DESCRIPTION + ", optional") @RequestParam(required = false) Instant to) {
		return analyticsService.responseTimesByEndpoint(new Period(from, to));
	}

	@GetMapping("/funnel")
	@Operation(summary = "Conversion funnel over a sequence of event types",
			description = """
					Counts the users who went through the given steps in that order, e.g. signup, then first \
					message, then subscription. For each user, the first occurrence of each step is kept; a \
					step only counts if it comes after the previous one. With from and to, only the events \
					of the period are taken into account.""")
	@ApiResponse(responseCode = "200", description = "One entry per step, in the order given")
	public List<FunnelStep> funnel(
			@Parameter(description = "Event types of the funnel, in order, at least 2 and without repetition")
			@RequestParam(defaultValue = "USER_SIGNED_UP,MESSAGE_SENT,SUBSCRIPTION_PAID") List<EventType> steps,
			@Parameter(description = FROM_DESCRIPTION + ", optional") @RequestParam(required = false) Instant from,
			@Parameter(description = TO_DESCRIPTION + ", optional") @RequestParam(required = false) Instant to) {
		return analyticsService.funnel(steps, new Period(from, to));
	}
}
