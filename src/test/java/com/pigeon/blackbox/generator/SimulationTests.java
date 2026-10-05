package com.pigeon.blackbox.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;

/* Runs the simulation in memory, without MongoDB, on a small but meaningful volume */
class SimulationTests {

	private static final GeneratorProperties PROPERTIES = new GeneratorProperties(7, 2025, 300, 30_000, 1.0, 1_000);

	private static final List<Event> events = new ArrayList<>();

	private static Simulation.Result result;

	@BeforeAll
	static void simulate() {
		result = Simulation.run(PROPERTIES, events::add);
	}

	@Test
	void producesExactlyTheRequestedNumberOfEvents() {
		assertThat(events).hasSize(PROPERTIES.events());
		assertThat(result.totalEvents()).isEqualTo(PROPERTIES.events());
		assertThat(result.users()).hasSize(PROPERTIES.users());
	}

	@Test
	void sameSeedGivesSameEvents() {
		List<Event> again = new ArrayList<>();
		Simulation.run(PROPERTIES, again::add);

		assertThat(again).isEqualTo(events);
	}

	@Test
	void everyEventHappensDuringTheYearAndAfterItsUserSignedUp() {
		Map<String, Instant> signups = signupsByUser();
		Instant yearStart = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ActivityCalendar.ZONE).toInstant();
		Instant yearEnd = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ActivityCalendar.ZONE).toInstant();

		assertThat(events).allSatisfy(event -> {
			assertThat(event.timestamp()).isBetween(yearStart, yearEnd);
			assertThat(event.timestamp()).isAfterOrEqualTo(signups.get(event.userId()));
		});
	}

	@Test
	void everyUserSignsUpExactlyOnce() {
		List<String> signedUp = events.stream()
			.filter(ofType(EventType.USER_SIGNED_UP))
			.map(event -> event.userId())
			.toList();

		assertThat(signedUp).hasSize(PROPERTIES.users()).doesNotHaveDuplicates();
	}

	@Test
	void funnelStepsHappenInOrderAndNarrowDown() {
		Map<String, Instant> signups = signupsByUser();
		Map<String, Instant> firstMessages = firstOccurrenceByUser(EventType.MESSAGE_SENT);
		Map<String, Instant> firstPayments = firstOccurrenceByUser(EventType.SUBSCRIPTION_PAID);

		assertThat(signups.size()).isGreaterThan(firstMessages.size());
		assertThat(firstMessages.size()).isGreaterThan(firstPayments.size());
		assertThat(firstPayments).isNotEmpty();
		firstMessages.forEach((userId, at) -> assertThat(at).isAfter(signups.get(userId)));
		firstPayments.forEach((userId, at) -> {
			assertThat(firstMessages).containsKey(userId);
			assertThat(at).isAfter(firstMessages.get(userId));
		});
	}

	@Test
	void nightsAreQuieterThanWorkingHours() {
		long night = events.stream().filter(atParisHourBetween(2, 5)).count();
		long morning = events.stream().filter(atParisHourBetween(9, 12)).count();

		assertThat(morning).isGreaterThan(5 * night);
	}

	@Test
	void weekendsAreQuieterThanWeekdays() {
		Map<LocalDate, Long> perDay = events.stream()
			.collect(Collectors.groupingBy(event -> parisDay(event), Collectors.counting()));
		double weekend = averageOver(perDay, day -> day.getDayOfWeek().getValue() >= 6);
		double weekday = averageOver(perDay, day -> day.getDayOfWeek().getValue() <= 5);

		assertThat(weekend).isLessThan(0.6 * weekday);
	}

	@Test
	void aFewUsersProduceMostOfTheActivity() {
		List<Long> eventsPerUser = events.stream()
			.collect(Collectors.groupingBy(event -> event.userId(), Collectors.counting()))
			.values()
			.stream()
			.sorted(Comparator.reverseOrder())
			.toList();
		long topTenPercent = eventsPerUser.stream().limit(PROPERTIES.users() / 10).mapToLong(count -> count).sum();

		assertThat(topTenPercent).isGreaterThan(events.size() / 3);
	}

	@Test
	void incidentDaysConcentrateErrors() {
		assertThat(result.incidentDays()).hasSize(3)
			.allSatisfy(day -> assertThat(day.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

		Predicate<Event> onIncidentDay = event -> result.incidentDays().contains(parisDay(event));
		double incidentErrorRate = errorRate(events.stream().filter(onIncidentDay).toList());
		double normalErrorRate = errorRate(events.stream().filter(onIncidentDay.negate()).toList());

		assertThat(incidentErrorRate).isGreaterThan(3 * normalErrorRate);
	}

	private static Predicate<Event> ofType(EventType type) {
		return event -> event.type() == type;
	}

	private static Map<String, Instant> signupsByUser() {
		return firstOccurrenceByUser(EventType.USER_SIGNED_UP);
	}

	private static Map<String, Instant> firstOccurrenceByUser(EventType type) {
		Map<String, Instant> first = new HashMap<>();
		events.stream()
			.filter(ofType(type))
			.forEach(event -> first.merge(event.userId(), event.timestamp(),
					(current, candidate) -> candidate.isBefore(current) ? candidate : current));
		return first;
	}

	private static LocalDate parisDay(Event event) {
		return event.timestamp().atZone(ActivityCalendar.ZONE).toLocalDate();
	}

	private static Predicate<Event> atParisHourBetween(int fromHour, int toHourExclusive) {
		return event -> {
			int hour = event.timestamp().atZone(ActivityCalendar.ZONE).getHour();
			return hour >= fromHour && hour < toHourExclusive;
		};
	}

	private static double averageOver(Map<LocalDate, Long> perDay, Predicate<LocalDate> daySelector) {
		return perDay.entrySet()
			.stream()
			.filter(entry -> daySelector.test(entry.getKey()))
			.mapToLong(entry -> entry.getValue())
			.average()
			.orElse(0);
	}

	private static double errorRate(List<Event> sample) {
		long errors = sample.stream().filter(ofType(EventType.APPLICATION_ERROR)).count();
		return (double) errors / sample.size();
	}
}
