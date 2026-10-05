package com.pigeon.blackbox.generator;

import java.util.List;

import com.pigeon.blackbox.event.Platform;
import com.pigeon.blackbox.event.payload.ApiCallPayload.HttpMethod;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.StackFrame;
import com.pigeon.blackbox.event.payload.NotificationSentPayload.Channel;

/* Fictitious but plausible values used to build users and payloads */
final class ReferenceData {

	private ReferenceData() {
	}

	static final List<String> FIRST_NAMES = List.of(
			"Léa", "Hugo", "Chloé", "Lucas", "Manon", "Nathan", "Camille", "Louis", "Inès", "Gabriel",
			"Sarah", "Jules", "Emma", "Adam", "Jade", "Raphaël", "Zoé", "Arthur", "Lina", "Noah",
			"Amira", "Yanis", "Fatou", "Moussa", "Mei", "Tom", "Alice", "Samuel", "Nora", "Elias");

	static final List<String> LAST_NAMES = List.of(
			"Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand", "Leroy", "Moreau",
			"Simon", "Laurent", "Lefebvre", "Michel", "Garcia", "David", "Bertrand", "Roux", "Vincent", "Fournier",
			"Diallo", "Nguyen", "Benali", "Traoré", "Mercier", "Blanc", "Guerin", "Faure", "Andre", "Chevalier");

	static final List<String> COMPANY_KINDS = List.of(
			"Atelier", "Studio", "Groupe", "Cabinet", "Agence", "Maison", "Collectif", "Bureau");

	static final List<String> COMPANY_NAMES = List.of(
			"Nord", "Horizon", "Azur", "Boréal", "Granit", "Orion", "Alto", "Vega", "Cobalt", "Lumen", "Sirius", "Opale");

	record Country(String code, double weight, List<String> cities) {
	}

	static final List<Country> COUNTRIES = List.of(
			new Country("FR", 0.60, List.of("Paris", "Lyon", "Marseille", "Toulouse", "Lille", "Bordeaux", "Nantes")),
			new Country("BE", 0.10, List.of("Bruxelles", "Liège", "Gand")),
			new Country("CH", 0.08, List.of("Genève", "Lausanne", "Zurich")),
			new Country("CA", 0.07, List.of("Montréal", "Québec")),
			new Country("DE", 0.05, List.of("Berlin", "Munich")),
			new Country("ES", 0.05, List.of("Madrid", "Barcelone")),
			new Country("IT", 0.05, List.of("Milan", "Rome")));

	/* Public API routes, with their typical (median) response time and their share of the calls */
	record Endpoint(HttpMethod method, String path, int medianResponseMs, double weight) {
	}

	static final List<Endpoint> ENDPOINTS = List.of(
			new Endpoint(HttpMethod.GET, "/api/v1/channels/{channelId}/messages", 90, 0.30),
			new Endpoint(HttpMethod.POST, "/api/v1/channels/{channelId}/messages", 140, 0.20),
			new Endpoint(HttpMethod.GET, "/api/v1/conversations/{conversationId}", 70, 0.15),
			new Endpoint(HttpMethod.GET, "/api/v1/users/{userId}", 45, 0.12),
			new Endpoint(HttpMethod.GET, "/api/v1/search", 380, 0.10),
			new Endpoint(HttpMethod.POST, "/api/v1/files", 650, 0.06),
			new Endpoint(HttpMethod.PUT, "/api/v1/channels/{channelId}", 160, 0.04),
			new Endpoint(HttpMethod.DELETE, "/api/v1/messages/{messageId}", 110, 0.02),
			new Endpoint(HttpMethod.GET, "/api/v1/exports/{exportId}", 1800, 0.01));

	record AttachmentType(String mimeType, long medianSizeBytes, double weight) {
	}

	static final List<AttachmentType> ATTACHMENT_TYPES = List.of(
			new AttachmentType("image/png", 150_000, 0.30),
			new AttachmentType("image/jpeg", 300_000, 0.25),
			new AttachmentType("application/pdf", 400_000, 0.25),
			new AttachmentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document", 80_000, 0.10),
			new AttachmentType("application/zip", 2_000_000, 0.10));

	/* The application frames that open the stack trace of each kind of error */
	record ErrorProfile(String service, String message, List<StackFrame> frames) {
	}

	static ErrorProfile errorProfile(ErrorType type) {
		return switch (type) {
			case DATABASE_TIMEOUT -> new ErrorProfile("messaging-service", "Query exceeded 5000 ms", List.of(
					new StackFrame("com.pigeon.messaging.MessageRepository", "findPage", 88),
					new StackFrame("com.pigeon.messaging.MessageService", "listMessages", 142),
					new StackFrame("com.pigeon.messaging.MessageController", "list", 57)));
			case UPSTREAM_UNAVAILABLE -> new ErrorProfile("notification-service", "Push gateway returned 503", List.of(
					new StackFrame("com.pigeon.notification.PushGatewayClient", "send", 64),
					new StackFrame("com.pigeon.notification.NotificationDispatcher", "dispatch", 118)));
			case NULL_REFERENCE -> new ErrorProfile("user-service", "Profile is null for the requested user", List.of(
					new StackFrame("com.pigeon.user.AvatarResolver", "resolve", 41),
					new StackFrame("com.pigeon.user.ProfileService", "loadProfile", 97),
					new StackFrame("com.pigeon.user.UserController", "get", 33)));
			case VALIDATION_FAILED -> new ErrorProfile("api-gateway", "Request body failed validation", List.of(
					new StackFrame("com.pigeon.gateway.RequestValidator", "validate", 76),
					new StackFrame("com.pigeon.gateway.GatewayFilter", "filter", 52)));
			case RATE_LIMIT_EXCEEDED -> new ErrorProfile("api-gateway", "Rate limit of 100 requests per minute exceeded",
					List.of(new StackFrame("com.pigeon.gateway.RateLimiter", "acquire", 29),
							new StackFrame("com.pigeon.gateway.GatewayFilter", "filter", 47)));
		};
	}

	/* Framework frames found below the application frames of a real Java stack trace */
	static final List<StackFrame> FRAMEWORK_FRAMES = List.of(
			new StackFrame("org.springframework.web.servlet.FrameworkServlet", "processRequest", 1014),
			new StackFrame("org.springframework.web.servlet.DispatcherServlet", "doDispatch", 1089),
			new StackFrame("org.apache.catalina.core.ApplicationFilterChain", "internalDoFilter", 195),
			new StackFrame("org.apache.catalina.core.ApplicationFilterChain", "doFilter", 140),
			new StackFrame("org.apache.catalina.core.StandardWrapperValve", "invoke", 165),
			new StackFrame("org.apache.coyote.http11.Http11Processor", "service", 397),
			new StackFrame("org.apache.tomcat.util.threads.ThreadPoolExecutor", "runWorker", 1190),
			new StackFrame("java.lang.Thread", "run", 1583));

	static List<String> notificationTemplates(Channel channel) {
		return switch (channel) {
			case EMAIL -> List.of("weekly_digest", "payment_receipt", "security_alert", "mention_digest");
			case PUSH -> List.of("new_message", "mention", "reaction");
			case SMS -> List.of("login_code", "security_alert");
		};
	}

	static String userAgent(Platform platform) {
		return switch (platform) {
			case WEB -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36";
			case DESKTOP -> "Pigeon-Desktop/4.2.1 (Windows 11)";
			case IOS -> "Pigeon-iOS/4.2.0 (iPhone; iOS 18.0)";
			case ANDROID -> "Pigeon-Android/4.2.0 (Android 14)";
			case API, SYSTEM -> throw new IllegalArgumentException("no user agent outside an interactive platform: " + platform);
		};
	}
}
