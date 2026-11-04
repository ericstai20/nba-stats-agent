package com.nbastats.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// A fake key lets this test run without a .env file. It never calls Claude.
@SpringBootTest(properties = "ANTHROPIC_API_KEY=test-key")
class AgentAppApplicationTests {

	@Test
	void contextLoads() {
	}

}
