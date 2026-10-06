package com.pigeon.blackbox.analytics;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.pigeon.blackbox.analytics.DailyErrors.ErrorTypeCount;
import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.user.Plan;

/*
 * Runs the pipelines of AnalyticsPipelines. MongoDB does all the counting: Java only reads the
 * few result documents and turns them into response objects.
 */
@Service
public class AnalyticsService {

	public static final int MAX_LIMIT = 100;

	private static final ZoneId PARIS = ZoneId.of(AnalyticsPipelines.TIME_ZONE);

	private final MongoTemplate mongoTemplate;

	public AnalyticsService(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	public List<TopUser> topActiveUsers(Period period, int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new InvalidAnalyticsQueryException("limit must be between 1 and " + MAX_LIMIT + ", got: " + limit);
		}
		return aggregate(AnalyticsPipelines.topActiveUsers(period, limit), document -> new TopUser(
				document.getString("userId"),
				document.getString("displayName"),
				document.getString("company"),
				document.getString("plan") == null ? null : Plan.valueOf(document.getString("plan")),
				count(document, "eventCount")));
	}

	public List<DailyErrors> errorsByTypeAndDay(Period period) {
		return aggregate(AnalyticsPipelines.errorsByTypeAndDay(period), document -> new DailyErrors(
				document.getDate("day").toInstant().atZone(PARIS).toLocalDate(),
				count(document, "total"),
				document.getList("byType", Document.class)
					.stream()
					.map(byType -> new ErrorTypeCount(ErrorType.valueOf(byType.getString("errorType")),
							count(byType, "count")))
					.toList()));
	}

	public List<EndpointResponseTime> responseTimesByEndpoint(Period period) {
		return aggregate(AnalyticsPipelines.responseTimesByEndpoint(period), document -> new EndpointResponseTime(
				HttpMethod.valueOf(document.getString("method")),
				document.getString("endpoint"),
				count(document, "calls"),
				document.get("averageMs", Number.class).doubleValue(),
				document.get("p95Ms", Number.class).doubleValue()));
	}

	public List<FunnelStep> funnel(List<EventType> steps, Period period) {
		if (steps.size() < 2) {
			throw new InvalidAnalyticsQueryException("a funnel needs at least 2 steps, got: " + steps);
		}
		if (new HashSet<>(steps).size() != steps.size()) {
			throw new InvalidAnalyticsQueryException("each step can appear only once, got: " + steps);
		}

		/* usersByStepsReached[k] = users who stopped after exactly k steps */
		long[] usersByStepsReached = new long[steps.size() + 1];
		for (Document row : aggregate(AnalyticsPipelines.funnel(steps, period), document -> document)) {
			usersByStepsReached[row.get("_id", Number.class).intValue()] = count(row, "users");
		}

		/* Users who reached step k = users who stopped at step k or later */
		long[] reached = new long[steps.size() + 1];
		for (int k = steps.size(); k >= 1; k--) {
			reached[k] = usersByStepsReached[k] + (k < steps.size() ? reached[k + 1] : 0);
		}

		List<FunnelStep> funnel = new ArrayList<>(steps.size());
		for (int k = 1; k <= steps.size(); k++) {
			funnel.add(new FunnelStep(k, steps.get(k - 1), reached[k],
					k == 1 ? 100.0 : percent(reached[k], reached[k - 1]), percent(reached[k], reached[1])));
		}
		return funnel;
	}

	private <T> List<T> aggregate(List<Document> pipeline, Function<Document, T> mapper) {
		List<T> results = new ArrayList<>();
		/* allowDiskUse: a $group or a $sort above 100 MB spills to disk instead of failing */
		for (Document document : mongoTemplate.getCollection(mongoTemplate.getCollectionName(Event.class))
			.aggregate(pipeline)
			.allowDiskUse(true)) {
			results.add(mapper.apply(document));
		}
		return results;
	}

	private static long count(Document document, String field) {
		return document.get(field, Number.class).longValue();
	}

	/* Rounded to one decimal */
	private static double percent(long part, long whole) {
		return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
	}
}
