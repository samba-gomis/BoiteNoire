package com.pigeon.blackbox.generator;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.EventPayload;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload.BillingPeriod;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload.PaymentMethod;
import com.pigeon.blackbox.generator.ReferenceData.Country;
import com.pigeon.blackbox.generator.SimulatedUser.Subscription;
import com.pigeon.blackbox.user.Plan;

/*
 * Phases 1 and 2: creates the user population and plays the funnel of each user,
 * signup, then maybe a first message, then maybe a subscription renewed until the end of the year.
 */
final class LifecycleSimulator {

	/* Users whose Zipf rank is in the top 20% are the "big" users */
	private static final double BIG_USERS_SHARE = 0.20;

	/* How much the signup order is blurred when ranking users: 0 = the first signed up is the biggest */
	private static final double RANK_NOISE = 0.5;

	private final GeneratorProperties properties;
	private final ActivityCalendar calendar;
	private final PayloadFactory payloads;
	private final RandomGenerator random;

	private final WeightedSampler<Country> countries = WeightedSampler.of(ReferenceData.COUNTRIES,
			country -> country.weight());
	private final WeightedSampler<Platform> platforms = new WeightedSampler<>(
			List.of(Platform.WEB, Platform.DESKTOP, Platform.IOS, Platform.ANDROID),
			new double[] { 0.45, 0.20, 0.20, 0.15 });
	private final WeightedSampler<PaymentMethod> paymentMethods = WeightedSampler.ofEnum(PaymentMethod.class, 0.60,
			0.30, 0.10);

	LifecycleSimulator(GeneratorProperties properties, ActivityCalendar calendar, PayloadFactory payloads,
			RandomGenerator random) {
		this.properties = properties;
		this.calendar = calendar;
		this.payloads = payloads;
		this.random = random;
	}

	/* Returns the users sorted by signup date; their funnel events go to the sink */
	List<SimulatedUser> simulate(Consumer<Event> sink) {
		int count = properties.users();
		List<Instant> signups = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			signups.add(calendar.randomInstant(calendar.randomSignupDay(random), random));
		}
		signups.sort(null);
		int[] ranks = ranksFavouringEarlyUsers(count);

		List<SimulatedUser> users = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SimulatedUser user = createUser(i, signups.get(i), ranks[i]);
			String referrerCandidateId = i == 0 ? null : users.get(random.nextInt(i)).id();
			users.add(user);
			emitFunnelEvents(user, referrerCandidateId, sink);
		}
		return users;
	}

	/*
	 * Early adopters tend to be the heaviest users: Zipf ranks follow the signup order, blurred by noise.
	 * Without this, the whole yearly curve would depend on the signup date of the two or three biggest users.
	 */
	private int[] ranksFavouringEarlyUsers(int count) {
		double[] keys = new double[count];
		for (int i = 0; i < count; i++) {
			keys[i] = (double) i / count + RANK_NOISE * random.nextDouble();
		}
		List<Integer> byKey = IntStream.range(0, count)
			.boxed()
			.sorted(Comparator.comparingDouble(index -> keys[index]))
			.toList();

		int[] ranks = new int[count];
		for (int position = 0; position < count; position++) {
			ranks[byKey.get(position)] = position + 1;
		}
		return ranks;
	}

	private SimulatedUser createUser(int index, Instant signedUpAt, int rank) {
		double rankShare = (double) rank / properties.users();
		boolean bigUser = rankShare <= BIG_USERS_SHARE;
		Country country = countries.next(random);

		String displayName = pick(ReferenceData.FIRST_NAMES) + " " + pick(ReferenceData.LAST_NAMES);
		String company = pick(ReferenceData.COMPANY_KINDS) + " " + pick(ReferenceData.COMPANY_NAMES);
		String apiKeyId = random.nextDouble() < (bigUser ? 0.50 : 0.10)
				? "key_%06x".formatted(random.nextInt(0x1000000)) : null;

		Instant firstMessageAt = null;
		if (random.nextDouble() < (bigUser ? 0.95 : 0.65)) {
			/* Most users write on their first day, the others a few days later */
			int daysLater = random.nextDouble() < 0.55 ? 0 : 1 + (int) PayloadFactory.logNormal(random, 3, 1.0);
			firstMessageAt = withinYear(later(signedUpAt, daysLater));
		}

		Subscription subscription = null;
		if (firstMessageAt != null && random.nextDouble() < subscriptionProbability(rankShare)) {
			Instant startAt = withinYear(later(firstMessageAt, 1 + (int) PayloadFactory.logNormal(random, 10, 0.8)));
			if (startAt != null) {
				Plan plan = random.nextDouble() < (bigUser ? 0.60 : 0.20) ? Plan.BUSINESS : Plan.PRO;
				int seats = plan == Plan.PRO ? 1 : (int) Math.clamp(2 + Math.round(PayloadFactory.logNormal(random, 6, 0.9)), 2, 80);
				BillingPeriod billingPeriod = random.nextDouble() < 0.25 ? BillingPeriod.YEARLY : BillingPeriod.MONTHLY;
				subscription = new Subscription(plan, billingPeriod, seats, paymentMethods.next(random), startAt);
			}
		}

		return new SimulatedUser("usr_%06d".formatted(index + 1), displayName, company, country.code(),
				pick(country.cities()), signedUpAt, 1 / Math.pow(rank, properties.zipfExponent()),
				platforms.next(random), apiKeyId, firstMessageAt, subscription);
	}

	/* The biggest users are the most likely to pay */
	private static double subscriptionProbability(double rankShare) {
		if (rankShare <= 0.05) {
			return 0.70;
		}
		return rankShare <= BIG_USERS_SHARE ? 0.45 : 0.15;
	}

	/* daysLater = 0: a few minutes later the same day; otherwise a realistic hour of a later day */
	private Instant later(Instant after, int daysLater) {
		if (daysLater <= 0) {
			return after.plus(Duration.ofMinutes(1 + Math.round(PayloadFactory.logNormal(random, 20, 1.0))));
		}
		return calendar.randomInstant(calendar.dayOf(after).plusDays(daysLater), random);
	}

	/* What would happen after December 31st is out of the simulation */
	private Instant withinYear(Instant instant) {
		return instant.isBefore(calendar.endOfYear()) ? instant : null;
	}

	private void emitFunnelEvents(SimulatedUser user, String referrerCandidateId, Consumer<Event> sink) {
		sink.accept(event(EventType.USER_SIGNED_UP, user.signedUpAt(), user, payloads.signUp(referrerCandidateId)));

		if (user.firstMessageAt() != null) {
			sink.accept(event(EventType.MESSAGE_SENT, user.firstMessageAt(), user, payloads.messageSent()));
		}

		Subscription subscription = user.subscription();
		if (subscription != null) {
			ZonedDateTime paymentAt = subscription.startAt().atZone(ActivityCalendar.ZONE);
			while (paymentAt.toInstant().isBefore(calendar.endOfYear())) {
				sink.accept(event(EventType.SUBSCRIPTION_PAID, paymentAt.toInstant(), user,
						payloads.subscriptionPaid(subscription)));
				paymentAt = subscription.billingPeriod() == BillingPeriod.MONTHLY ? paymentAt.plusMonths(1)
						: paymentAt.plusYears(1);
			}
		}
	}

	private Event event(EventType type, Instant at, SimulatedUser user, EventPayload payload) {
		Platform platform = user.preferredPlatform();
		return Event.of(type, at, user.id(), user.sessionIdAt(at, platform), platform, payload);
	}

	private <T> T pick(List<T> values) {
		return values.get(random.nextInt(values.size()));
	}
}
