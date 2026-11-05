package com.nbastats.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests the /ask web endpoint. MockMvc sends pretend web requests straight to the
 * controller, like FastAPI's TestClient does in the Python tests.
 */
class AskControllerTest {

	private FakeServer fakeClaude;
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws IOException {
		fakeClaude = new FakeServer();
		// This test never reaches the stats service, so its address doesn't matter.
		ClaudeService claude = new ClaudeService("test-key", "fake-model", fakeClaude.url(),
				new StatsClient("http://127.0.0.1:1"));
		mvc = MockMvcBuilders.standaloneSetup(new AskController(claude)).build();
	}

	@AfterEach
	void tearDown() {
		fakeClaude.close();
	}

	private ResultActions ask(String json) throws Exception {
		return mvc.perform(post("/ask").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void returnsTheAnswerAsJson() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeText("Jokić leads the league."));

		ask("{\"question\":\"Who leads the league?\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("Jokić leads the league."));
	}

	@Test
	void rejectsABlankQuestionWithoutPayingForARequest() throws Exception {
		ask("{\"question\":\"   \"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("question: must not be blank"));

		assertThat(fakeClaude.requests()).isEmpty();
	}

	@Test
	void rejectsAMissingQuestion() throws Exception {
		ask("{}").andExpect(status().isBadRequest());
	}

	@Test
	void turnsAClaudeFailureIntoAFriendlyMessage() throws Exception {
		// 401 is what Claude's API sends for a bad API key.
		fakeClaude.reply(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");

		ask("{\"question\":\"Who leads the league?\"}")
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").value("Couldn't get an answer from Claude. Please try again shortly."));
	}

}
