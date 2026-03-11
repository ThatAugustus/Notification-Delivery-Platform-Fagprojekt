package com.app.demo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires running database — re-enable when Testcontainers is set up")
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
