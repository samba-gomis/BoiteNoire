package com.pigeon.blackbox.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.EventPayload;

/*
 * Phase 3: spreads the daily activity over the year. Each day gets a share of the volume
 * from its weight and from the users already signed up; each event gets an hour from the
 * hour curve and a user drawn by Zipf weight.
 */
final class ActivitySimulator {

	private static final List<EventType> ACTIVITY_TYPES = List.of(EventType.USER_LOGGED_IN, EventType.MESSAGE_SENT,
			EventType.API_CALLED, EventType.NOTIFICATION_SENT, EventType.APPLICATION_ERROR);

	/* Shares of ACTIVITY_TYPES on a normal day */
	private static final double[] NORMAL_MIX = { 0.14, 0.46, 0.25, 0.12, 0.03 };

	/* During an incident, errors are about eight times more frequent */
	private static final double[] INCIDENT_MIX = { 0.14, 0.46, 0.25, 0.12, 0.24 };

	private static final List<Platform> INTERACTIVE_PLATFORMS = List.of(Platform.WEB, Platform.DESKTOP, Platform.IOS,
			Platform.ANDROID);

	/* Share of the events of a user coming from its usual platform */
	private static final double PREFERRED_PLATFORM_SHARE = 0.80;

	private final ActivityCalendar calendar;
	private final PayloadFactory payloads;
	private final RandomGenerator random;

	private final WeightedSampler<EventType> normalMix = new WeightedSampler<>(ACTIVITY_TYPES, NORMAL_MIX);
	private final WeightedSampler<EventType> incidentMix = new WeightedSampler<>(ACTIVITY_TYPES, INCIDENT_MIX);

	ActivitySimulator(ActivityCalendar calendar, PayloadFactory payloads, RandomGenerator random) {
		this.calendar = calendar;
		this.payloads = payloads;
		this.random = random;
	}

	/* users must be sorted by signup date; returns the number of events sent to the sink */
	long simulate(List<SimulatedUser> users, long budget, Consumer<Event> sink) {
		if (budget <= 0) {
			return 0;
		}
		WeightedSampler<SimulatedUser> userSampler = WeightedSampler.of(users, user -> user.activityWeight());
		List<LocalDate> days = calendar.days();

		/* Only the users signed up before a day can be active that day: the volume grows with the user base */
		int[] activeUsers = new int[days.size()];
		double[] weights = new double[days.size()];
		double totalWeight = 0;
		int signedUp = 0;
		for (int d = 0; d < days.size(); d++) {
			Instant dayStart = calendar.startOf(days.get(d));
			while (signedUp < users.size() && users.get(signedUp).signedUpAt().isBefore(dayStart)) {
				signedUp++;
			}
			activeUsers[d] = signedUp;
			weights[d] = calendar.dayWeight(d) * userSampler.totalWeightOfFirst(signedUp);
			totalWeight += weights[d];
		}
		if (totalWeight == 0) {
			return 0;
		}

		/* Cumulative rounding: the daily counts add up exactly to the budget */
		long emitted = 0;
		double cumulativeWeight = 0;
		for (int d = 0; d < days.size(); d++) {
			if (weights[d] == 0) {
				continue;
			}
			cumulativeWeight += weights[d];
			long target = Math.round(budget * cumulativeWeight / totalWeight);
			LocalDate day = days.get(d);
			boolean incident = calendar.isIncident(day);
			for (long n = emitted; n < target; n++) {
				SimulatedUser user = userSampler.nextAmongFirst(activeUsers[d], random);
				Instant at = calendar.randomInstant(day, random);
				EventType type = (incident ? incidentMix : normalMix).next(random);
				sink.accept(activityEvent(type, user, at, incident));
			}
			emitted = target;
		}
		return emitted;
	}

	private Event activityEvent(EventType type, SimulatedUser user, Instant at, boolean incident) {
		return switch (type) {
			case MESSAGE_SENT -> messageOrLogin(user, at);
			/* Only the users holding an API key call the public API */
			case API_CALLED -> user.hasApiKey()
					? Event.of(EventType.API_CALLED, at, user.id(), null, Platform.API,
							payloads.apiCall(user.apiKeyId(), incident))
					: messageOrLogin(user, at);
			case NOTIFICATION_SENT -> Event.of(EventType.NOTIFICATION_SENT, at, user.id(), null, Platform.SYSTEM,
					payloads.notificationSent(at));
			case APPLICATION_ERROR -> applicationError(user, at, incident);
			default -> login(user, at);
		};
	}

	/* Before its first message, a user can only log in: the first message belongs to the funnel */
	private Event messageOrLogin(SimulatedUser user, Instant at) {
		if (!user.canSendMessagesAt(at)) {
			return login(user, at);
		}
		return interactiveEvent(EventType.MESSAGE_SENT, user, at, payloads.messageSent());
	}

	private Event login(SimulatedUser user, Instant at) {
		Platform platform = interactivePlatform(user);
		return Event.of(EventType.USER_LOGGED_IN, at, user.id(), user.sessionIdAt(at, platform), platform,
				payloads.login(user, platform));
	}

	/* Errors of API users partly come from their API calls, the others from the apps */
	private Event applicationError(SimulatedUser user, Instant at, boolean incident) {
		if (user.hasApiKey() && random.nextDouble() < 0.40) {
			return Event.of(EventType.APPLICATION_ERROR, at, user.id(), null, Platform.API,
					payloads.applicationError(incident));
		}
		return interactiveEvent(EventType.APPLICATION_ERROR, user, at, payloads.applicationError(incident));
	}

	private Event interactiveEvent(EventType type, SimulatedUser user, Instant at, EventPayload payload) {
		Platform platform = interactivePlatform(user);
		return Event.of(type, at, user.id(), user.sessionIdAt(at, platform), platform, payload);
	}

	private Platform interactivePlatform(SimulatedUser user) {
		if (random.nextDouble() < PREFERRED_PLATFORM_SHARE) {
			return user.preferredPlatform();
		}
		return INTERACTIVE_PLATFORMS.get(random.nextInt(INTERACTIVE_PLATFORMS.size()));
	}
}
