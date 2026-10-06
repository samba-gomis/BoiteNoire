package com.pigeon.blackbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/* Startup creates the indexes: the test database keeps the generated data untouched */
@SpringBootTest(properties = "spring.mongodb.uri=mongodb://localhost:27017/blackbox_test")
class BlackboxApplicationTests {

	@Test
	void contextLoads() {
	}

}
