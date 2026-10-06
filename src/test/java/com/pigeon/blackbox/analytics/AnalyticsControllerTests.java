package com.pigeon.blackbox.analytics;

import static com.pigeon.blackbox.analytics.TestData.error;
import static com.pigeon.blackbox.analytics.TestData.login;
import static com.pigeon.blackbox.analytics.TestData.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.pigeon.blackbox.event.Event;
import com.pigeon.blackbox.event.payload.ApplicationErrorPayload.ErrorType;
import com.pigeon.blackbox.user.User;

/* The HTTP side: parameters, JSON format, 400 answers and Swagger documentation */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/blackbox_test")
class AnalyticsControllerTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private MongoTemplate mongoTemplate;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
		mongoTemplate.dropCollection(Event.class);
		mongoTemplate.dropCollection(User.class);
	}

	@Test
	void topUsersAreReturnedAsJson() throws Exception {
		mongoTemplate.insert(user("usr_a", "Alice Martin"));
		mongoTemplate.insertAll(List.of(login("usr_a", "2025-03-02T10:00:00Z"), login("usr_a", "2025-03-03T10:00:00Z")));

		mockMvc.perform(get("/api/analytics/top-users")
			.param("from", "2025-03-01T00:00:00Z")
			.param("to", "2025-04-01T00:00:00Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].userId").value("usr_a"))
			.andExpect(jsonPath("$[0].displayName").value("Alice Martin"))
			.andExpect(jsonPath("$[0].eventCount").value(2));
	}

	@Test
	void errorDaysAreIsoDates() throws Exception {
		mongoTemplate.insert(error("usr_a", "2025-03-10T09:00:00Z", ErrorType.DATABASE_TIMEOUT));

		mockMvc.perform(get("/api/analytics/errors")
			.param("from", "2025-03-01T00:00:00Z")
			.param("to", "2025-04-01T00:00:00Z"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].day").value("2025-03-10"))
			.andExpect(jsonPath("$[0].byType[0].errorType").value("DATABASE_TIMEOUT"));
	}

	@Test
	void funnelUsesSignupMessageAndSubscriptionByDefault() throws Exception {
		mockMvc.perform(get("/api/analytics/funnel"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].step").value("USER_SIGNED_UP"))
			.andExpect(jsonPath("$[1].step").value("MESSAGE_SENT"))
			.andExpect(jsonPath("$[2].step").value("SUBSCRIPTION_PAID"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			/* from is missing */
			"/api/analytics/top-users?to=2025-04-01T00:00:00Z",
			/* from after to */
			"/api/analytics/top-users?from=2025-04-01T00:00:00Z&to=2025-03-01T00:00:00Z",
			/* limit below 1 */
			"/api/analytics/top-users?from=2025-03-01T00:00:00Z&to=2025-04-01T00:00:00Z&limit=0",
			/* unparsable date */
			"/api/analytics/errors?from=yesterday&to=2025-04-01T00:00:00Z",
			/* a single step */
			"/api/analytics/funnel?steps=USER_SIGNED_UP",
			/* unknown event type */
			"/api/analytics/funnel?steps=USER_SIGNED_UP,UNKNOWN_TYPE" })
	void invalidRequestsGetA400WithAProblemDetail(String url) throws Exception {
		mockMvc.perform(get(url))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void theFourAnalysesAreDocumentedInOpenApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/analytics/top-users'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/analytics/errors'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/analytics/response-times'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/analytics/funnel'].get.summary").exists());
	}
}
