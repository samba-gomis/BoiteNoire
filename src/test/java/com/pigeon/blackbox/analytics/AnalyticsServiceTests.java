package com.pigeon.blackbox.analytics;

import static com.pigeon.blackbox.analytics.TestData.apiCall;
import static com.pigeon.blackbox.analytics.TestData.error;
import static com.pigeon.blackbox.analytics.TestData.login;
import static com.pigeon.blackbox.analytics.TestData.message;
import static com.pigeon.blackbox.analytics.TestData.notification;
import static com.pigeon.blackbox.analytics.TestData.payment;
import static com.pigeon.blackbox.analytics.TestData.signUp;
import static com.pigeon.blackbox.analytics.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.pigeon.blackbox.analytics.DailyErrors.ErrorTypeCount;
import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.user.Plan;
import com.pigeon.blackbox.user.User;

/* Each pipeline runs on a tiny dataset whose expected result is known in advance */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/blackbox_test")
class AnalyticsServiceTests {

	private static final Period MARCH = new Period(Instant.parse("2025-03-01T00:00:00Z"),
			Instant.parse("2025-04-01T00:00:00Z"));

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private AnalyticsService analyticsService;

	@BeforeEach
	void cleanDatabase() {
		mongoTemplate.dropCollection(Event.class);
		mongoTemplate.dropCollection(User.class);
	}

	@Test
	void topActiveUsersCountsOnlyTheUserInitiatedEventsOfThePeriod() {
		mongoTemplate.insertAll(List.of(user("usr_a", "Alice Martin"), user("usr_b", "Bruno Petit")));
		mongoTemplate.insertAll(List.of(
				login("usr_a", "2025-03-02T10:00:00Z"),
				message("usr_a", "2025-03-03T10:00:00Z"),
				message("usr_a", "2025-03-04T10:00:00Z"),
				notification("usr_a", "2025-03-05T10:00:00Z"),
				error("usr_a", "2025-03-05T11:00:00Z", ErrorType.NULL_REFERENCE),
				login("usr_a", "2025-04-02T10:00:00Z"),
				login("usr_b", "2025-03-10T10:00:00Z"),
				message("usr_b", "2025-03-11T10:00:00Z"),
				login("usr_c", "2025-03-12T10:00:00Z")));

		List<TopUser> top = analyticsService.topActiveUsers(MARCH, 10);

		/* usr_a: the notification, the error and the April login are not counted */
		assertThat(top).extracting(topUser -> topUser.userId(), topUser -> topUser.eventCount())
			.containsExactly(tuple("usr_a", 3L), tuple("usr_b", 2L), tuple("usr_c", 1L));
		assertThat(top.get(0).displayName()).isEqualTo("Alice Martin");
		assertThat(top.get(0).plan()).isEqualTo(Plan.FREE);
		/* usr_c has no profile: still listed, without profile fields */
		assertThat(top.get(2).displayName()).isNull();

		assertThat(analyticsService.topActiveUsers(MARCH, 2)).extracting(topUser -> topUser.userId())
			.containsExactly("usr_a", "usr_b");
	}

	@Test
	void errorsAreGroupedByParisDayThenByType() {
		mongoTemplate.insertAll(List.of(
				error("usr_a", "2025-03-10T09:00:00Z", ErrorType.DATABASE_TIMEOUT),
				error("usr_b", "2025-03-10T12:00:00Z", ErrorType.NULL_REFERENCE),
				/* 23:30 in Paris: still March 10 */
				error("usr_b", "2025-03-10T22:30:00Z", ErrorType.DATABASE_TIMEOUT),
				/* 00:30 in Paris: already March 11 */
				error("usr_a", "2025-03-10T23:30:00Z", ErrorType.VALIDATION_FAILED),
				login("usr_a", "2025-03-10T10:00:00Z")));

		assertThat(analyticsService.errorsByTypeAndDay(MARCH)).containsExactly(
				new DailyErrors(LocalDate.of(2025, 3, 10), 3, List.of(
						new ErrorTypeCount(ErrorType.DATABASE_TIMEOUT, 2),
						new ErrorTypeCount(ErrorType.NULL_REFERENCE, 1))),
				new DailyErrors(LocalDate.of(2025, 3, 11), 1, List.of(
						new ErrorTypeCount(ErrorType.VALIDATION_FAILED, 1))));
	}

	@Test
	void responseTimesGiveTheAverageAndTheP95OfEachMethodAndRoute() {
		List<Event> calls = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			calls.add(apiCall("2025-03-02T10:00:00Z", HttpMethod.GET, "/api/v1/search", 10 * i));
		}
		calls.add(apiCall("2025-03-02T10:00:00Z", HttpMethod.POST, "/api/v1/search", 1000));
		mongoTemplate.insertAll(calls);

		List<EndpointResponseTime> times = analyticsService.responseTimesByEndpoint(Period.unbounded());

		assertThat(times).hasSize(2);
		assertThat(times.get(0)).isEqualTo(new EndpointResponseTime(HttpMethod.POST, "/api/v1/search", 1, 1000, 1000));
		EndpointResponseTime search = times.get(1);
		assertThat(search.method()).isEqualTo(HttpMethod.GET);
		assertThat(search.calls()).isEqualTo(20);
		assertThat(search.averageMs()).isEqualTo(105.0);
		/* 10, 20, ..., 200 ms: 95% of the calls answer in 190 ms or less */
		assertThat(search.p95Ms()).isBetween(180.0, 200.0);
	}

	@Test
	void funnelCountsTheUsersWhoTookTheStepsInOrder() {
		mongoTemplate.insertAll(List.of(
				/* u1: the three steps in order */
				signUp("u1", "2025-03-01T10:00:00Z"),
				message("u1", "2025-03-01T11:00:00Z"),
				payment("u1", "2025-03-05T10:00:00Z"),
				/* u2: two steps; a second message does not count twice */
				signUp("u2", "2025-03-02T10:00:00Z"),
				message("u2", "2025-03-02T11:00:00Z"),
				message("u2", "2025-03-03T11:00:00Z"),
				/* u3: signup only */
				signUp("u3", "2025-03-03T10:00:00Z"),
				/* u4: paid before the first message, so the payment is out of order: two steps */
				signUp("u4", "2025-03-04T10:00:00Z"),
				payment("u4", "2025-03-04T11:00:00Z"),
				message("u4", "2025-03-04T12:00:00Z"),
				/* u5: a message without signup: does not enter the funnel */
				message("u5", "2025-03-05T10:00:00Z")));

		List<FunnelStep> funnel = analyticsService.funnel(
				List.of(EventType.USER_SIGNED_UP, EventType.MESSAGE_SENT, EventType.SUBSCRIPTION_PAID),
				Period.unbounded());

		assertThat(funnel).containsExactly(
				new FunnelStep(1, EventType.USER_SIGNED_UP, 4, 100.0, 100.0),
				new FunnelStep(2, EventType.MESSAGE_SENT, 3, 75.0, 75.0),
				new FunnelStep(3, EventType.SUBSCRIPTION_PAID, 1, 33.3, 25.0));
	}

	@Test
	void funnelRejectsTooFewOrRepeatedSteps() {
		assertThatThrownBy(() -> analyticsService.funnel(List.of(EventType.USER_SIGNED_UP), Period.unbounded()))
			.isInstanceOf(InvalidAnalyticsQueryException.class);
		assertThatThrownBy(() -> analyticsService.funnel(List.of(EventType.USER_SIGNED_UP, EventType.USER_SIGNED_UP),
				Period.unbounded()))
			.isInstanceOf(InvalidAnalyticsQueryException.class);
	}

	@Test
	void aPeriodMustEndAfterItStarts() {
		assertThatThrownBy(() -> new Period(Instant.parse("2025-04-01T00:00:00Z"), Instant.parse("2025-03-01T00:00:00Z")))
			.isInstanceOf(InvalidAnalyticsQueryException.class)
			.hasMessageContaining("from must be before to");
	}
}
