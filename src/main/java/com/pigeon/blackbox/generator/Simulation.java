package com.pigeon.blackbox.generator;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import com.pigeon.blackbox.event.Event;

/* Chains the three phases with a single seeded random generator: same seed, same events */
final class Simulation {

	record Result(List<SimulatedUser> users, Set<LocalDate> incidentDays, long funnelEvents, long activityEvents) {

		long totalEvents() {
			return funnelEvents + activityEvents;
		}
	}

	private Simulation() {
	}

	static Result run(GeneratorProperties properties, Consumer<Event> sink) {
		RandomGenerator random = new SplittableRandom(properties.seed());
		ActivityCalendar calendar = new ActivityCalendar(properties.year(), random);
		PayloadFactory payloads = new PayloadFactory(random, properties.year());

		AtomicLong funnelEvents = new AtomicLong();
		List<SimulatedUser> users = new LifecycleSimulator(properties, calendar, payloads, random).simulate(event -> {
			funnelEvents.incrementAndGet();
			sink.accept(event);
		});

		/* The daily activity fills the volume left after the funnel events */
		long activityEvents = new ActivitySimulator(calendar, payloads, random).simulate(users,
				properties.events() - funnelEvents.get(), sink);

		return new Result(users, calendar.incidentDays(), funnelEvents.get(), activityEvents);
	}
}
