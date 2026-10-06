package com.pigeon.blackbox.performance;

import static com.pigeon.blackbox.analytics.TestData.login;
import static com.pigeon.blackbox.analytics.TestData.message;
import static com.pigeon.blackbox.analytics.TestData.payment;
import static com.pigeon.blackbox.analytics.TestData.signUp;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.pigeon.blackbox.analytics.AnalyticsPipelines;
import com.pigeon.blackbox.analytics.Period;
import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventIndexes;
import com.pigeon.blackbox.event.EventType;

/* The index must keep its field order and keep the funnel and the top users covered: no document opened */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/blackbox_test")
class IndexCoverageTests {

	private static final Period MARCH = new Period(Instant.parse("2025-03-01T00:00:00Z"),
			Instant.parse("2025-04-01T00:00:00Z"));

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private EventIndexes eventIndexes;

	@BeforeEach
	void prepareCollection() {
		mongoTemplate.dropCollection(Event.class);
		mongoTemplate.insertAll(List.of(
				signUp("u1", "2025-03-01T10:00:00Z"),
				login("u1", "2025-03-01T10:30:00Z"),
				message("u1", "2025-03-01T11:00:00Z"),
				payment("u1", "2025-03-05T10:00:00Z"),
				signUp("u2", "2025-03-02T10:00:00Z"),
				message("u2", "2025-03-02T11:00:00Z")));
		eventIndexes.ensureIndexes();
	}

	@Test
	void indexFieldsAreTypeThenTimestampThenUserId() {
		Document index = mongoTemplate.getCollection("events")
			.listIndexes()
			.into(new ArrayList<>())
			.stream()
			.filter(candidate -> EventIndexes.TYPE_TIMESTAMP_USER_ID.equals(candidate.getString("name")))
			.findFirst()
			.orElseThrow();

		assertThat(new ArrayList<>(index.get("key", Document.class).keySet())).containsExactly("type", "timestamp",
				"userId");
	}

	@Test
	void funnelIsCoveredByTheIndex() {
		assertCovered(AnalyticsPipelines.funnel(
				List.of(EventType.USER_SIGNED_UP, EventType.MESSAGE_SENT, EventType.SUBSCRIPTION_PAID), MARCH));
	}

	@Test
	void topActiveUsersIsCoveredByTheIndex() {
		assertCovered(AnalyticsPipelines.topActiveUsers(MARCH, 10));
	}

	private void assertCovered(List<Document> pipeline) {
		Document explain = ExplainReader.explain(mongoTemplate, "events", pipeline);
		Document stats = ExplainReader.executionStats(explain);

		assertThat(ExplainReader.plan(explain)).contains("IXSCAN").doesNotContain("COLLSCAN").doesNotContain("FETCH");
		assertThat(ExplainReader.count(stats, "totalKeysExamined")).isPositive();
		assertThat(ExplainReader.count(stats, "totalDocsExamined")).isZero();
	}
}
