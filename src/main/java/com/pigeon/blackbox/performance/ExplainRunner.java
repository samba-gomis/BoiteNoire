package com.pigeon.blackbox.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.pigeon.blackbox.analytics.AnalyticsPipelines;
import com.pigeon.blackbox.analytics.Period;
import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;

/*
 * Measures the pipelines of the four analyses with explain("executionStats"), runs only with the
 * "explain" Spring profile. The pipelines come from AnalyticsPipelines: what is measured is exactly
 * what the API runs. The report is written to docs/performance/explain-<label>.json.
 */
@Component
@Profile("explain")
@EnableConfigurationProperties(ExplainProperties.class)
class ExplainRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(ExplainRunner.class);

	/* Periods of the default dataset (generator.year = 2025): a whole year, and one month */
	private static final Period YEAR = new Period(Instant.parse("2025-01-01T00:00:00Z"),
			Instant.parse("2026-01-01T00:00:00Z"));
	private static final Period MARCH = new Period(Instant.parse("2025-03-01T00:00:00Z"),
			Instant.parse("2025-04-01T00:00:00Z"));

	private static final List<EventType> FUNNEL_STEPS = List.of(EventType.USER_SIGNED_UP, EventType.MESSAGE_SENT,
			EventType.SUBSCRIPTION_PAID);

	private static final JsonWriterSettings JSON = JsonWriterSettings.builder()
		.outputMode(JsonMode.RELAXED)
		.indent(true)
		.build();

	record Scenario(String name, List<Document> pipeline) {
	}

	record Measure(String plan, long docsExamined, long keysExamined, long matchedDocuments, int returnedDocuments,
			long explainMillis, long pipelineMillis, Document explain) {
	}

	private final MongoTemplate mongoTemplate;
	private final ExplainProperties properties;

	ExplainRunner(MongoTemplate mongoTemplate, ExplainProperties properties) {
		this.mongoTemplate = mongoTemplate;
		this.properties = properties;
	}

	private static List<Scenario> scenarios() {
		return List.of(
				new Scenario("top-users, year", AnalyticsPipelines.topActiveUsers(YEAR, 10)),
				new Scenario("top-users, March", AnalyticsPipelines.topActiveUsers(MARCH, 10)),
				new Scenario("errors, year", AnalyticsPipelines.errorsByTypeAndDay(YEAR)),
				new Scenario("errors, March", AnalyticsPipelines.errorsByTypeAndDay(MARCH)),
				new Scenario("response-times, year", AnalyticsPipelines.responseTimesByEndpoint(YEAR)),
				new Scenario("response-times, March", AnalyticsPipelines.responseTimesByEndpoint(MARCH)),
				new Scenario("funnel, year", AnalyticsPipelines.funnel(FUNNEL_STEPS, YEAR)),
				new Scenario("funnel, March", AnalyticsPipelines.funnel(FUNNEL_STEPS, MARCH)));
	}

	@Override
	public void run(String... args) throws IOException {
		Path output = Path.of(properties.outputDirectory(), "explain-" + properties.label() + ".json");
		/* A committed measure is evidence: it is never overwritten by mistake */
		if (Files.exists(output)) {
			throw new IllegalStateException(output + " already exists: delete it or choose another explain.label");
		}

		MongoCollection<Document> events = mongoTemplate.getCollection(mongoTemplate.getCollectionName(Event.class));
		List<Document> indexes = events.listIndexes().into(new ArrayList<>());
		long eventCount = events.countDocuments();
		log.info("Measuring the analyses on {} events, {} runs each, indexes: {}", eventCount, properties.runs(),
				indexes.stream().map(index -> index.getString("name")).toList());

		List<Document> results = new ArrayList<>();
		for (Scenario scenario : scenarios()) {
			Measure measure = measure(events, scenario.pipeline());
			log.info(String.format(Locale.ROOT,
					"%-22s | docs examined %,7d | keys examined %,7d | matched %,7d | returned %,4d | explain %,5d ms | pipeline %,5d ms | %s",
					scenario.name(), measure.docsExamined(), measure.keysExamined(), measure.matchedDocuments(),
					measure.returnedDocuments(), measure.explainMillis(), measure.pipelineMillis(), measure.plan()));
			results.add(new Document("name", scenario.name())
				.append("pipeline", scenario.pipeline())
				.append("plan", measure.plan())
				.append("totalDocsExamined", measure.docsExamined())
				.append("totalKeysExamined", measure.keysExamined())
				.append("matchedDocuments", measure.matchedDocuments())
				.append("returnedDocuments", measure.returnedDocuments())
				.append("explainExecutionTimeMillisMedian", measure.explainMillis())
				.append("pipelineMillisMedian", measure.pipelineMillis())
				.append("explain", measure.explain()));
		}

		Document report = new Document("label", properties.label())
			.append("measuredAt", Date.from(Instant.now()))
			.append("mongoVersion", mongoTemplate.getDb().runCommand(new Document("buildInfo", 1)).getString("version"))
			.append("eventCount", eventCount)
			.append("indexes", indexes)
			.append("runs", properties.runs())
			.append("scenarios", results);
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output, report.toJson(JSON));
		log.info("Report written to {}", output.toAbsolutePath());
	}

	private Measure measure(MongoCollection<Document> events, List<Document> pipeline) {
		/* Warm-up: the first run pays for loading the data in memory */
		int returned = events.aggregate(pipeline).allowDiskUse(true).into(new ArrayList<>()).size();

		List<Long> pipelineMillis = new ArrayList<>();
		List<Long> explainMillis = new ArrayList<>();
		Document explain = null;
		for (int run = 0; run < properties.runs(); run++) {
			long start = System.nanoTime();
			events.aggregate(pipeline).allowDiskUse(true).into(new ArrayList<>());
			pipelineMillis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());

			explain = mongoTemplate.getDb()
				.runCommand(new Document("explain", new Document("aggregate", events.getNamespace().getCollectionName())
					.append("pipeline", pipeline)
					.append("cursor", new Document())
					.append("allowDiskUse", true))
					.append("verbosity", "executionStats"));
			explainMillis.add(count(executionStats(explain), "executionTimeMillis"));
		}

		Document stats = executionStats(explain);
		Document match = pipeline.get(0).get("$match", Document.class);
		return new Measure(plan(explain), count(stats, "totalDocsExamined"), count(stats, "totalKeysExamined"),
				events.countDocuments(match), returned, median(explainMillis), median(pipelineMillis), explain);
	}

	/* Depending on the pipeline, explain puts executionStats at the top or under stages[0].$cursor */
	private static Document executionStats(Document explain) {
		Document stats = find(explain, "executionStats");
		if (stats == null) {
			throw new IllegalStateException("no executionStats in the explain output: " + explain.toJson());
		}
		return stats;
	}

	/*
	 * Stages of the winning plan, from the collection to the result, e.g. "COLLSCAN -> GROUP",
	 * followed by the pipeline stages run outside of the query engine, e.g. "then $sort, $lookup"
	 */
	private static String plan(Document explain) {
		Document winningPlan = find(explain, "queryPlanner").get("winningPlan", Document.class);
		Object queryPlan = winningPlan.containsKey("queryPlan") ? winningPlan.get("queryPlan") : winningPlan;
		List<String> stages = new ArrayList<>();
		collectStages(queryPlan, stages);
		Collections.reverse(stages);
		String plan = String.join(" -> ", stages);

		List<String> otherStages = new ArrayList<>();
		for (Document stage : explain.getList("stages", Document.class, List.of())) {
			String name = stage.keySet().iterator().next();
			if (!name.equals("$cursor")) {
				otherStages.add(name);
			}
		}
		return otherStages.isEmpty() ? plan : plan + ", then " + String.join(", ", otherStages);
	}

	private static void collectStages(Object node, List<String> stages) {
		if (node instanceof Document document) {
			if (document.get("stage") instanceof String stage) {
				stages.add(stage);
			}
			document.values().forEach(value -> collectStages(value, stages));
		}
		else if (node instanceof List<?> list) {
			list.forEach(value -> collectStages(value, stages));
		}
	}

	/* First sub-document stored under the given key, searched depth-first */
	private static Document find(Object node, String key) {
		if (node instanceof Document document) {
			if (document.get(key) instanceof Document found) {
				return found;
			}
			for (Object value : document.values()) {
				Document found = find(value, key);
				if (found != null) {
					return found;
				}
			}
		}
		else if (node instanceof List<?> list) {
			for (Object value : list) {
				Document found = find(value, key);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static long count(Document document, String field) {
		return document.get(field, Number.class).longValue();
	}

	private static long median(List<Long> values) {
		List<Long> sorted = values.stream().sorted().toList();
		return (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2;
	}
}
