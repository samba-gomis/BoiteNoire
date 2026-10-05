package com.pigeon.blackbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.EventType;
import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.ApiCallPayload;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload;
import com.pigeon.blackbox.event.payload.LoginPayload;
import com.pigeon.blackbox.event.payload.MessageSentPayload;
import com.pigeon.blackbox.event.payload.NotificationSentPayload;
import com.pigeon.blackbox.event.payload.SignUpPayload;
import com.pigeon.blackbox.event.payload.SubscriptionPaidPayload;
import com.pigeon.blackbox.user.Plan;
import com.pigeon.blackbox.user.User;

/* Uses a dedicated database so the tests never touch the generated data */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/blackbox_test")
class DocumentMappingTests {

	@Autowired
	private MongoTemplate mongoTemplate;

	@BeforeEach
	void cleanDatabase() {
		mongoTemplate.dropCollection(Event.class);
		mongoTemplate.dropCollection(User.class);
	}

	@Test
	void samplesCoverEveryEventType() {
		assertThat(sampleEvents()).extracting(event -> event.type()).containsExactlyInAnyOrder(EventType.values());
	}

	@Test
	void everyEventTypeIsReadBackWithItsOwnPayload() {
		List<Event> originals = sampleEvents();
		mongoTemplate.insertAll(originals);

		List<Event> stored = mongoTemplate.findAll(Event.class);

		assertThat(stored).hasSameSizeAs(originals);
		for (Event original : originals) {
			Event found = stored.stream().filter(event -> event.type() == original.type()).findFirst().orElseThrow();
			assertThat(found.id()).isNotNull();
			assertThat(found.payload()).isInstanceOf(original.type().payloadType());
			assertThat(found).isEqualTo(new Event(found.id(), original.type(), original.timestamp(),
					original.userId(), original.sessionId(), original.platform(), original.payload()));
		}
	}

	@Test
	void eventsAreStoredWithTheBsonTypesOfTheSchema() {
		mongoTemplate.insertAll(sampleEvents());

		Document payment = Objects.requireNonNull(
				mongoTemplate.getCollection("events").find(new Document("type", "SUBSCRIPTION_PAID")).first(),
				"no SUBSCRIPTION_PAID event stored");
		Document payload = Objects.requireNonNull(payment.get("payload", Document.class), "payload not stored");

		assertThat(payment.get("_id")).isInstanceOf(ObjectId.class);
		assertThat(payment.get("type")).isEqualTo("SUBSCRIPTION_PAID");
		assertThat(payment.get("timestamp")).isInstanceOf(Date.class);
		assertThat(payload.get("amount")).isInstanceOf(Decimal128.class);
	}

	@Test
	void userKeepsItsReadableStringId() {
		User user = new User("usr_000042", "Léa Martin", "Atelier Nord", "FR", Plan.BUSINESS,
				Instant.parse("2025-03-02T09:41:00Z"));
		mongoTemplate.insert(user);

		Document stored = Objects.requireNonNull(mongoTemplate.getCollection("users").find().first(),
				"no user stored");

		assertThat(stored.get("_id")).isEqualTo("usr_000042");
		assertThat(mongoTemplate.findById("usr_000042", User.class)).isEqualTo(user);
	}

	/* One event per type, matching the examples of docs/document-schema.md */
	private static List<Event> sampleEvents() {
		String userId = "usr_000042";
		return List.of(
				Event.of(EventType.USER_SIGNED_UP, Instant.parse("2025-03-02T09:41:00Z"), userId, "ses_8c1f2a",
						Platform.WEB,
						new SignUpPayload(SignUpPayload.AcquisitionChannel.REFERRAL, "usr_000007", Plan.FREE)),
				Event.of(EventType.USER_LOGGED_IN, Instant.parse("2025-03-14T08:12:44Z"), userId, "ses_b47e90",
						Platform.WEB,
						new LoginPayload(LoginPayload.AuthMethod.PASSWORD, true, false, "203.0.113.24",
								"Mozilla/5.0 (Windows NT 10.0; Win64; x64)", new LoginPayload.Geo("FR", "Lyon"))),
				Event.of(EventType.MESSAGE_SENT, Instant.parse("2025-03-14T08:15:03Z"), userId, "ses_b47e90",
						Platform.WEB,
						new MessageSentPayload("conv_51e9", MessageSentPayload.ConversationType.GROUP, 6, 284,
								List.of(new MessageSentPayload.Attachment("application/pdf", 482113),
										new MessageSentPayload.Attachment("image/png", 90544)))),
				Event.of(EventType.SUBSCRIPTION_PAID, Instant.parse("2025-03-20T17:02:51Z"), userId, "ses_d2093c",
						Platform.WEB,
						new SubscriptionPaidPayload(new BigDecimal("96.00"), "EUR", Plan.BUSINESS,
								SubscriptionPaidPayload.BillingPeriod.MONTHLY, 8,
								SubscriptionPaidPayload.PaymentMethod.CARD, "inv_2025_003418")),
				Event.of(EventType.APPLICATION_ERROR, Instant.parse("2025-04-02T10:27:19Z"), userId, "ses_0f61aa",
						Platform.IOS,
						new ApplicationErrorPayload(ApplicationErrorPayload.ErrorType.DATABASE_TIMEOUT,
								ApplicationErrorPayload.Severity.ERROR, "messaging-service",
								"/api/v1/conversations/{conversationId}/messages", "Query exceeded 5000 ms",
								List.of(new ApplicationErrorPayload.StackFrame(
										"com.pigeon.messaging.MessageRepository", "findPage", 88)))),
				Event.of(EventType.API_CALLED, Instant.parse("2025-04-02T10:31:57Z"), userId, null, Platform.API,
						new ApiCallPayload(ApiCallPayload.HttpMethod.GET, "/api/v1/channels/{channelId}/messages",
								200, 143, "key_3f9a")),
				Event.of(EventType.NOTIFICATION_SENT, Instant.parse("2025-04-07T07:00:02Z"), userId, null,
						Platform.SYSTEM,
						new NotificationSentPayload(NotificationSentPayload.Channel.EMAIL, "weekly_digest", true,
								List.of(new NotificationSentPayload.DeliveryAttempt(
										Instant.parse("2025-04-07T07:00:02Z"),
										NotificationSentPayload.DeliveryOutcome.TIMEOUT),
										new NotificationSentPayload.DeliveryAttempt(
												Instant.parse("2025-04-07T07:05:02Z"),
												NotificationSentPayload.DeliveryOutcome.DELIVERED)))));
	}
}
