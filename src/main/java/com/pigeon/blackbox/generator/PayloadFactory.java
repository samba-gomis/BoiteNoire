package com.pigeon.blackbox.generator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.ApiCallPayload;
import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.Severity;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.StackFrame;
import com.pigeon.blackbox.event.payload.LoginPayload;
import com.pigeon.blackbox.event.payload.LoginPayload.AuthMethod;
import com.pigeon.blackbox.event.payload.MessageSentPayload;
import com.pigeon.blackbox.event.payload.MessageSentPayload.Attachment;
import com.pigeon.blackbox.event.payload.MessageSentPayload.ConversationType;
import com.pigeon.blackbox.event.payload.NotificationSentPayload;
import com.pigeon.blackbox.event.payload.NotificationSentPayload.Channel;
import com.pigeon.blackbox.event.payload.NotificationSentPayload.DeliveryAttempt;
import com.pigeon.blackbox.event.payload.NotificationSentPayload.DeliveryOutcome;
import com.pigeon.blackbox.event.payload.SignUpPayload;
import com.pigeon.blackbox.event.payload.SignUpPayload.AcquisitionChannel;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload.BillingPeriod;
import com.pigeon.blackbox.generator.ReferenceData.AttachmentType;
import com.pigeon.blackbox.generator.ReferenceData.Endpoint;
import com.pigeon.blackbox.generator.ReferenceData.ErrorProfile;
import com.pigeon.blackbox.user.Plan;

/* Builds realistic payloads, always valid for the checks of the event model */
final class PayloadFactory {

	private static final BigDecimal PRO_MONTHLY_PRICE = new BigDecimal("9.00");
	private static final BigDecimal BUSINESS_MONTHLY_PRICE_PER_SEAT = new BigDecimal("12.00");
	/* Yearly billing: 10 months paid for 12 */
	private static final BigDecimal YEARLY_MONTHS_PAID = BigDecimal.TEN;

	/* Documentation networks (RFC 5737): never real addresses */
	private static final List<String> IP_NETWORKS = List.of("192.0.2.", "198.51.100.", "203.0.113.");

	private static final double LOGIN_SUCCESS_RATE = 0.96;
	private static final double ATTACHMENT_RATE = 0.12;
	private static final double DELIVERY_SUCCESS_RATE = 0.90;
	private static final Duration DELIVERY_RETRY_DELAY = Duration.ofMinutes(5);
	/* During an incident the API answers about three times slower */
	private static final double INCIDENT_SLOWDOWN = 3.0;

	private final RandomGenerator random;
	private final int year;
	private int invoiceCounter;

	private final WeightedSampler<AcquisitionChannel> acquisitionChannels = WeightedSampler
		.ofEnum(AcquisitionChannel.class, 0.45, 0.25, 0.20, 0.10);
	/* Same shares without REFERRAL, for the very first user who has nobody to be referred by */
	private final WeightedSampler<AcquisitionChannel> acquisitionChannelsWithoutReferral = WeightedSampler
		.ofEnum(AcquisitionChannel.class, 0.45, 0.25, 0.00, 0.10);
	private final WeightedSampler<AuthMethod> authMethods = WeightedSampler.ofEnum(AuthMethod.class, 0.50, 0.25, 0.15,
			0.10);
	private final WeightedSampler<ConversationType> conversationTypes = WeightedSampler
		.ofEnum(ConversationType.class, 0.55, 0.30, 0.15);
	private final WeightedSampler<AttachmentType> attachmentTypes = WeightedSampler
		.of(ReferenceData.ATTACHMENT_TYPES, type -> type.weight());
	private final WeightedSampler<Endpoint> endpoints = WeightedSampler.of(ReferenceData.ENDPOINTS,
			endpoint -> endpoint.weight());
	private final WeightedSampler<ErrorType> errorTypes = WeightedSampler.ofEnum(ErrorType.class, 0.20, 0.15, 0.25,
			0.25, 0.15);
	/* During an incident, the database and the upstream services are the ones failing */
	private final WeightedSampler<ErrorType> incidentErrorTypes = WeightedSampler.ofEnum(ErrorType.class, 0.55, 0.30,
			0.05, 0.05, 0.05);
	private final WeightedSampler<Severity> severities = WeightedSampler.ofEnum(Severity.class, 0.30, 0.60, 0.10);
	private final WeightedSampler<Severity> incidentSeverities = WeightedSampler.ofEnum(Severity.class, 0.10, 0.60,
			0.30);
	private final WeightedSampler<Channel> notificationChannels = WeightedSampler.ofEnum(Channel.class, 0.35, 0.55,
			0.10);

	PayloadFactory(RandomGenerator random, int year) {
		this.random = random;
		this.year = year;
	}

	/* Value spread around a median, with a long tail: response times, sizes, delays */
	static double logNormal(RandomGenerator random, double median, double sigma) {
		return median * Math.exp(sigma * random.nextGaussian());
	}

	/* referrerCandidateId: an earlier user, or null when nobody signed up before */
	SignUpPayload signUp(String referrerCandidateId) {
		AcquisitionChannel channel = referrerCandidateId == null ? acquisitionChannelsWithoutReferral.next(random)
				: acquisitionChannels.next(random);
		String referrerUserId = channel == AcquisitionChannel.REFERRAL ? referrerCandidateId : null;
		return new SignUpPayload(channel, referrerUserId, Plan.FREE);
	}

	LoginPayload login(SimulatedUser user, Platform platform) {
		String ip = IP_NETWORKS.get(random.nextInt(IP_NETWORKS.size())) + (1 + random.nextInt(254));
		return new LoginPayload(authMethods.next(random), random.nextDouble() < LOGIN_SUCCESS_RATE,
				random.nextDouble() < 0.30, ip, ReferenceData.userAgent(platform),
				new LoginPayload.Geo(user.country(), user.city()));
	}

	MessageSentPayload messageSent() {
		ConversationType type = conversationTypes.next(random);
		int recipientCount = switch (type) {
			case DIRECT -> 1;
			case GROUP -> 2 + random.nextInt(11);
			case CHANNEL -> 5 + (int) Math.min(500, logNormal(random, 25, 0.8));
		};
		List<Attachment> attachments = random.nextDouble() < ATTACHMENT_RATE ? attachments(1 + random.nextInt(3))
				: List.of();
		/* Some messages are only a file, without text */
		int contentLength = !attachments.isEmpty() && random.nextDouble() < 0.30 ? 0
				: (int) Math.clamp(Math.round(logNormal(random, 80, 0.9)), 1, 4000);
		String conversationId = "conv_%05x".formatted(random.nextInt(0x40000));
		return new MessageSentPayload(conversationId, type, recipientCount, contentLength, attachments);
	}

	private List<Attachment> attachments(int count) {
		List<Attachment> attachments = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			AttachmentType type = attachmentTypes.next(random);
			long sizeBytes = Math.max(1, Math.round(logNormal(random, type.medianSizeBytes(), 0.8)));
			attachments.add(new Attachment(type.mimeType(), sizeBytes));
		}
		return attachments;
	}

	SubscriptionPaidPayload subscriptionPaid(SimulatedUser.Subscription subscription) {
		BigDecimal monthlyAmount = subscription.plan() == Plan.PRO ? PRO_MONTHLY_PRICE
				: BUSINESS_MONTHLY_PRICE_PER_SEAT.multiply(BigDecimal.valueOf(subscription.seats()));
		BigDecimal amount = subscription.billingPeriod() == BillingPeriod.YEARLY
				? monthlyAmount.multiply(YEARLY_MONTHS_PAID) : monthlyAmount;
		invoiceCounter++;
		return new SubscriptionPaidPayload(amount.setScale(2), "EUR", subscription.plan(),
				subscription.billingPeriod(), subscription.seats(), subscription.paymentMethod(),
				"inv_%d_%06d".formatted(year, invoiceCounter));
	}

	ApplicationErrorPayload applicationError(boolean incident) {
		ErrorType type = (incident ? incidentErrorTypes : errorTypes).next(random);
		ErrorProfile profile = ReferenceData.errorProfile(type);

		/* Real traces are long: the payload keeps only the first frames */
		List<StackFrame> stackTrace = new ArrayList<>(profile.frames());
		int frameworkFrames = 5 + random.nextInt(26);
		for (int i = 0; i < frameworkFrames; i++) {
			stackTrace.add(ReferenceData.FRAMEWORK_FRAMES.get(i % ReferenceData.FRAMEWORK_FRAMES.size()));
		}

		Severity severity = (incident ? incidentSeverities : severities).next(random);
		return new ApplicationErrorPayload(type, severity, profile.service(), endpoints.next(random).path(),
				profile.message(), stackTrace);
	}

	ApiCallPayload apiCall(String apiKeyId, boolean incident) {
		Endpoint endpoint = endpoints.next(random);
		double median = endpoint.medianResponseMs() * (incident ? INCIDENT_SLOWDOWN : 1);
		int responseTimeMs = (int) Math.round(logNormal(random, median, 0.5));
		return new ApiCallPayload(endpoint.method(), endpoint.path(), statusCode(endpoint.method(), incident),
				responseTimeMs, apiKeyId);
	}

	private int statusCode(HttpMethod method, boolean incident) {
		double serverErrorRate = incident ? 0.08 : 0.008;
		double draw = random.nextDouble();
		if (draw < serverErrorRate) {
			return random.nextDouble() < 0.7 ? 500 : 503;
		}
		if (draw < serverErrorRate + 0.01) {
			return 429;
		}
		if (draw < serverErrorRate + 0.05) {
			return random.nextDouble() < 0.5 ? 400 : 404;
		}
		return switch (method) {
			case POST -> 201;
			case DELETE -> 204;
			default -> 200;
		};
	}

	NotificationSentPayload notificationSent(Instant firstAttemptAt) {
		Channel channel = notificationChannels.next(random);
		List<String> templates = ReferenceData.notificationTemplates(channel);
		String template = templates.get(random.nextInt(templates.size()));

		List<DeliveryAttempt> attempts = new ArrayList<>();
		Instant attemptAt = firstAttemptAt;
		boolean delivered = false;
		while (!delivered && attempts.size() < NotificationSentPayload.MAX_ATTEMPTS) {
			delivered = random.nextDouble() < DELIVERY_SUCCESS_RATE;
			DeliveryOutcome outcome = delivered ? DeliveryOutcome.DELIVERED
					: random.nextDouble() < 0.6 ? DeliveryOutcome.TIMEOUT : DeliveryOutcome.BOUNCED;
			attempts.add(new DeliveryAttempt(attemptAt, outcome));
			attemptAt = attemptAt.plus(DELIVERY_RETRY_DELAY);
		}
		return new NotificationSentPayload(channel, template, delivered, attempts);
	}
}
