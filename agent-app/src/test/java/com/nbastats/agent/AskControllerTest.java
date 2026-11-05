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

import com.jayway.jsonpath.JsonPath;

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
		mvc = MockMvcBuilders.standaloneSetup(new AskController(claude, new ConversationStore())).build();
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

	/** Reads a conversation ID out of a reply. JsonPath finds a value inside JSON. */
	private String conversationIdOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.conversationId");
	}

	@Test
	void aFollowUpSendsTheEarlierQuestionAndAnswerToClaude() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeText("Luka Doncic led in scoring."));
		fakeClaude.reply(200, FakeServer.claudeText("He averaged 8 assists."));

		String id = conversationIdOf(ask("{\"question\":\"Who led in scoring?\"}"));
		ask("{\"question\":\"How about his assists?\",\"conversationId\":\"" + id + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("He averaged 8 assists."))
				.andExpect(jsonPath("$.conversationId").value(id));

		assertThat(fakeClaude.requests().get(1).body())
				.contains("Who led in scoring?")
				.contains("Luka Doncic led in scoring.")
				.contains("How about his assists?");
	}

	@Test
	void aQuestionWithoutAnIdKnowsNothingOfOtherConversations() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeText("First answer."));
		fakeClaude.reply(200, FakeServer.claudeText("Second answer."));

		String first = conversationIdOf(ask("{\"question\":\"First question\"}"));
		String second = conversationIdOf(ask("{\"question\":\"Second question\"}"));

		assertThat(second).isNotEqualTo(first);
		assertThat(fakeClaude.requests().get(1).body()).doesNotContain("First question");
	}

	@Test
	void anUnknownConversationGets404WithoutPayingForARequest() throws Exception {
		ask("{\"question\":\"How about his assists?\",\"conversationId\":\"forgotten\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value(
						"That conversation is no longer available. Your next question will start a new one."));

		assertThat(fakeClaude.requests()).isEmpty();
	}

	@Test
	void aRefusalIsNotRemembered() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeText("First answer."));
		fakeClaude.reply(200, FakeServer.claudeMessage("refusal"));
		fakeClaude.reply(200, FakeServer.claudeText("Third answer."));

		String id = conversationIdOf(ask("{\"question\":\"First question\"}"));
		ask("{\"question\":\"Declined question\",\"conversationId\":\"" + id + "\"}")
				.andExpect(jsonPath("$.conversationId").value(id));
		ask("{\"question\":\"Third question\",\"conversationId\":\"" + id + "\"}");

		assertThat(fakeClaude.requests().get(2).body())
				.contains("First question")
				.doesNotContain("Declined question");
	}

	@Test
	void aRefusedFirstQuestionDoesNotStartAConversation() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeMessage("refusal"));

		ask("{\"question\":\"Declined question\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conversationId").doesNotExist());
	}

	@Test
	void aClaudeFailureLeavesTheConversationUsable() throws Exception {
		fakeClaude.reply(200, FakeServer.claudeText("First answer."));
		fakeClaude.reply(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
		fakeClaude.reply(200, FakeServer.claudeText("Third answer."));

		String id = conversationIdOf(ask("{\"question\":\"First question\"}"));
		ask("{\"question\":\"Failed question\",\"conversationId\":\"" + id + "\"}")
				.andExpect(status().isServiceUnavailable());
		ask("{\"question\":\"Third question\",\"conversationId\":\"" + id + "\"}")
				.andExpect(status().isOk());

		assertThat(fakeClaude.requests().get(2).body())
				.contains("First question")
				.doesNotContain("Failed question");
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
