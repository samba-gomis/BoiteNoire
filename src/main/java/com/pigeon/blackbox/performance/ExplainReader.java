package com.pigeon.blackbox.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/* Runs explain("executionStats") on an aggregation and reads its output, whose shape depends on the pipeline */
final class ExplainReader {

	private ExplainReader() {
	}

	static Document explain(MongoTemplate mongoTemplate, String collection, List<Document> pipeline) {
		return mongoTemplate.getDb()
			.runCommand(new Document("explain", new Document("aggregate", collection)
				.append("pipeline", pipeline)
				.append("cursor", new Document())
				.append("allowDiskUse", true))
				.append("verbosity", "executionStats"));
	}

	/* explain puts executionStats at the top or under stages[0].$cursor, depending on the pipeline */
	static Document executionStats(Document explain) {
		Document stats = find(explain, "executionStats");
		if (stats == null) {
			throw new IllegalStateException("no executionStats in the explain output: " + explain.toJson());
		}
		return stats;
	}

	static long count(Document document, String field) {
		return document.get(field, Number.class).longValue();
	}

	/*
	 * Stages of the winning plan, from the collection to the result, e.g. "COLLSCAN -> GROUP",
	 * followed by the pipeline stages run outside of the query engine, e.g. "then $sort, $lookup"
	 */
	static String plan(Document explain) {
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
}
