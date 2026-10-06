package com.pigeon.blackbox.analytics;

import java.util.Date;
import java.util.List;

import org.bson.Document;

import com.pigeon.blackbox.event.EventType;

/*
 * The four aggregation pipelines, written stage by stage as they would be typed in mongosh:
 * they can be replayed as is with explain() to measure them. Every pipeline starts with $match,
 * the only stage able to use an index.
 */
final class AnalyticsPipelines {

	/* Days are cut at midnight, Paris time, like the generated activity */
	static final String TIME_ZONE = "Europe/Paris";

	private AnalyticsPipelines() {
	}

	static List<Document> topActiveUsers(Period period, int limit) {
		Document match = new Document("type", new Document("$in", names(EventType.userInitiatedTypes())));
		return List.of(
				new Document("$match", withPeriod(match, period)),
				new Document("$group", new Document("_id", "$userId").append("eventCount", new Document("$sum", 1))),
				new Document("$sort", new Document("eventCount", -1).append("_id", 1)),
				new Document("$limit", limit),
				/* Referencing in action: the profile is joined after $limit, for the kept users only */
				new Document("$lookup", new Document("from", "users")
					.append("localField", "_id")
					.append("foreignField", "_id")
					.append("as", "user")),
				/* No foreign key: a user without profile is kept, with empty profile fields */
				new Document("$unwind", new Document("path", "$user").append("preserveNullAndEmptyArrays", true)),
				new Document("$project", new Document("_id", 0)
					.append("userId", "$_id")
					.append("displayName", "$user.displayName")
					.append("company", "$user.company")
					.append("plan", "$user.plan")
					.append("eventCount", 1)));
	}

	static List<Document> errorsByTypeAndDay(Period period) {
		Document day = new Document("$dateTrunc", new Document("date", "$timestamp")
			.append("unit", "day")
			.append("timezone", TIME_ZONE));
		return List.of(
				new Document("$match", withPeriod(new Document("type", EventType.APPLICATION_ERROR.name()), period)),
				new Document("$group", new Document("_id", new Document("day", day).append("errorType", "$payload.errorType"))
					.append("count", new Document("$sum", 1))),
				new Document("$group", new Document("_id", "$_id.day")
					.append("total", new Document("$sum", "$count"))
					.append("byType", new Document("$push",
							new Document("errorType", "$_id.errorType").append("count", "$count")))),
				new Document("$sort", new Document("_id", 1)),
				new Document("$project", new Document("_id", 0)
					.append("day", "$_id")
					.append("total", 1)
					.append("byType", new Document("$sortArray", new Document("input", "$byType")
						.append("sortBy", new Document("count", -1).append("errorType", 1))))));
	}

	static List<Document> responseTimesByEndpoint(Period period) {
		/* t-digest estimation (MongoDB 7.0+): exact enough for a P95, without sorting every value */
		Document p95 = new Document("$percentile", new Document("input", "$payload.responseTimeMs")
			.append("p", List.of(0.95))
			.append("method", "approximate"));
		return List.of(
				new Document("$match", withPeriod(new Document("type", EventType.API_CALLED.name()), period)),
				/* GET and POST on the same route behave differently: grouped by method and route */
				new Document("$group", new Document("_id", new Document("method", "$payload.method")
					.append("endpoint", "$payload.endpoint"))
					.append("calls", new Document("$sum", 1))
					.append("averageMs", new Document("$avg", "$payload.responseTimeMs"))
					.append("p95Ms", p95)),
				new Document("$project", new Document("_id", 0)
					.append("method", "$_id.method")
					.append("endpoint", "$_id.endpoint")
					.append("calls", 1)
					.append("averageMs", new Document("$round", List.of("$averageMs", 1)))
					.append("p95Ms", new Document("$round", List.of(new Document("$first", "$p95Ms"), 1)))),
				new Document("$sort", new Document("p95Ms", -1).append("endpoint", 1).append("method", 1)));
	}

	/*
	 * Keeps the first occurrence of each step for each user, puts them in chronological order,
	 * then walks that sequence: the user moves to the next step only when the expected type comes.
	 * Returns one document per number of steps reached: { _id: reached, users: count }.
	 */
	static List<Document> funnel(List<EventType> steps, Period period) {
		List<String> stepNames = names(steps);
		Document nextStepIfExpected = new Document("$cond", List.of(
				new Document("$eq", List.of("$$this", new Document("$arrayElemAt", List.of(stepNames, "$$value")))),
				new Document("$add", List.of("$$value", 1)),
				"$$value"));
		return List.of(
				new Document("$match", withPeriod(new Document("type", new Document("$in", stepNames)), period)),
				new Document("$group", new Document("_id", new Document("userId", "$userId").append("type", "$type"))
					.append("firstAt", new Document("$min", "$timestamp"))),
				new Document("$sort", new Document("_id.userId", 1).append("firstAt", 1)),
				new Document("$group", new Document("_id", "$_id.userId")
					.append("sequence", new Document("$push", "$_id.type"))),
				new Document("$project", new Document("reached", new Document("$reduce", new Document("input", "$sequence")
					.append("initialValue", 0)
					.append("in", nextStepIfExpected)))),
				new Document("$group", new Document("_id", "$reached").append("users", new Document("$sum", 1))));
	}

	/* Adds the condition on timestamp only for the bounds given */
	private static Document withPeriod(Document match, Period period) {
		Document timestamp = new Document();
		if (period.from() != null) {
			timestamp.append("$gte", Date.from(period.from()));
		}
		if (period.to() != null) {
			timestamp.append("$lt", Date.from(period.to()));
		}
		if (!timestamp.isEmpty()) {
			match.append("timestamp", timestamp);
		}
		return match;
	}

	private static List<String> names(List<EventType> types) {
		return types.stream().map(type -> type.name()).toList();
	}
}
