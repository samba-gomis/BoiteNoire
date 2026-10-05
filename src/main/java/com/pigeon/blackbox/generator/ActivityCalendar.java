package com.pigeon.blackbox.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/* When things happen: weight of each day of the simulated year, hour curve and incident days */
final class ActivityCalendar {

	/* Pigeon's users live mostly in France: daily rhythms follow Paris time */
	static final ZoneId ZONE = ZoneId.of("Europe/Paris");

	/* Share of activity per hour: quiet nights, peaks around 10:00 and 15:00, lunch dip */
	private static final double[] HOUR_WEIGHTS = {
			0.15, 0.10, 0.07, 0.05, 0.05, 0.08, 0.20, 0.50, // 00:00 - 07:59
			1.00, 1.60, 1.80, 1.70, 1.20, 1.30, 1.70, 1.80, // 08:00 - 15:59
			1.60, 1.30, 0.90, 0.70, 0.60, 0.50, 0.35, 0.25 }; // 16:00 - 23:59

	/* Monday to Sunday: a professional tool is barely used on weekends */
	private static final double[] WEEKDAY_WEIGHTS = { 1.00, 1.05, 1.05, 1.00, 0.90, 0.35, 0.25 };

	/* January to December: summer and Christmas holidays are quieter */
	private static final double[] MONTH_WEIGHTS = {
			0.95, 1.00, 1.05, 1.00, 0.95, 1.00, 0.80, 0.55, 1.05, 1.05, 1.00, 0.75 };

	/* Day-to-day variation around the expected volume */
	private static final double DAILY_NOISE = 0.15;

	private static final int INCIDENT_DAYS = 3;

	private final List<LocalDate> days;
	private final double[] dayWeights;
	private final Set<LocalDate> incidentDays;
	private final WeightedSampler<Integer> hours;
	private final WeightedSampler<LocalDate> signupDays;

	ActivityCalendar(int year, RandomGenerator random) {
		LocalDate firstDay = LocalDate.of(year, 1, 1);
		days = firstDay.datesUntil(firstDay.plusYears(1)).toList();

		dayWeights = new double[days.size()];
		double[] signupWeights = new double[days.size()];
		for (int i = 0; i < days.size(); i++) {
			LocalDate day = days.get(i);
			double noise = Math.max(0.5, 1 + DAILY_NOISE * random.nextGaussian());
			dayWeights[i] = WEEKDAY_WEIGHTS[day.getDayOfWeek().ordinal()] * MONTH_WEIGHTS[day.getMonthValue() - 1] * noise;
			/* Signups grow over the year, from half to one and a half times the average */
			signupWeights[i] = dayWeights[i] * (0.5 + (double) i / days.size());
		}

		incidentDays = pickIncidentDays(random);
		hours = new WeightedSampler<>(IntStream.range(0, 24).boxed().toList(), HOUR_WEIGHTS);
		signupDays = new WeightedSampler<>(days, signupWeights);
	}

	private Set<LocalDate> pickIncidentDays(RandomGenerator random) {
		List<LocalDate> workingDays = days.stream().filter(day -> day.getDayOfWeek().getValue() <= 5).toList();
		Set<LocalDate> picked = new TreeSet<>();
		while (picked.size() < INCIDENT_DAYS) {
			picked.add(workingDays.get(random.nextInt(workingDays.size())));
		}
		return Collections.unmodifiableSet(picked);
	}

	List<LocalDate> days() {
		return days;
	}

	double dayWeight(int dayIndex) {
		return dayWeights[dayIndex];
	}

	Set<LocalDate> incidentDays() {
		return incidentDays;
	}

	boolean isIncident(LocalDate day) {
		return incidentDays.contains(day);
	}

	LocalDate randomSignupDay(RandomGenerator random) {
		return signupDays.next(random);
	}

	/* A moment of the given day, following the hour curve */
	Instant randomInstant(LocalDate day, RandomGenerator random) {
		LocalTime time = LocalTime.of(hours.next(random), random.nextInt(60), random.nextInt(60),
				random.nextInt(1000) * 1_000_000);
		return day.atTime(time).atZone(ZONE).toInstant();
	}

	LocalDate dayOf(Instant instant) {
		return instant.atZone(ZONE).toLocalDate();
	}

	Instant startOf(LocalDate day) {
		return day.atStartOfDay(ZONE).toInstant();
	}

	Instant endOfYear() {
		return startOf(days.getLast().plusDays(1));
	}
}
