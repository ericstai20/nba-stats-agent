package com.nbastats.agent;

import static com.nbastats.agent.FakeServer.claudeMessage;
import static com.nbastats.agent.FakeServer.claudeText;
import static com.nbastats.agent.FakeServer.claudeToolUse;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests the agent loop with a scripted fake Claude, so they cost no credits. Claude's
 * real answers vary, but a script always says the same thing, which lets these tests
 * check what my code does in each situation.
 */
class ClaudeServiceTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private FakeServer fakeClaude;
	private FakeServer fakeStats;
	private ClaudeService service;

	// @BeforeEach runs before every test, so each one starts with brand-new servers.
	@BeforeEach
	void setUp() throws IOException {
		fakeClaude = new FakeServer();
		fakeStats = new FakeServer();
		service = new ClaudeService("test-key", "fake-model", fakeClaude.url(), new StatsClient(fakeStats.url()));
	}

	@AfterEach
	void tearDown() {
		fakeClaude.close();
		fakeStats.close();
	}

	/** The last message in the conversation that the app sent on one request to Claude. */
	private JsonNode lastMessageOfRequest(int index) throws IOException {
		JsonNode messages = JSON.readTree(fakeClaude.requests().get(index).body()).get("messages");
		return messages.get(messages.size() - 1);
	}

	/** Asks a question at the start of a conversation and returns the answer's text. */
	private String ask(String question) {
		return service.ask(List.of(), question).text();
	}

	@Test
	void sendsEarlierQuestionsAndAnswersBeforeTheNewQuestion() throws IOException {
		fakeClaude.reply(200, claudeText("He averaged 8 assists."));

		service.ask(List.of(new ConversationStore.Exchange("Who led in scoring?", "Luka Doncic.")),
				"How about his assists?");

		JsonNode messages = JSON.readTree(fakeClaude.requests().getFirst().body()).get("messages");
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
		assertThat(messages.get(0).toString()).contains("Who led in scoring?");
		assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
		assertThat(messages.get(1).toString()).contains("Luka Doncic.");
		assertThat(messages.get(2).toString()).contains("How about his assists?");
	}

	@Test
	void marksRealAnswersAsComplete() {
		fakeClaude.reply(200, claudeText("An answer."));

		assertThat(service.ask(List.of(), "A question").complete()).isTrue();
	}

	@Test
	void marksAnEmptyAnswerAsIncompleteSoItIsNeverRemembered() {
		fakeClaude.reply(200, claudeMessage("end_turn"));

		ClaudeService.Answer answer = service.ask(List.of(), "A question");

		assertThat(answer.complete()).isFalse();
		assertThat(answer.text()).contains("didn't write an answer");
	}

	@Test
	void marksGivingUpAsIncomplete() {
		for (int turn = 1; turn <= 10; turn++) {
			fakeClaude.reply(200, claudeMessage("tool_use",
					claudeToolUse("call_" + turn, "search_players", "{\"name\":\"Curry\"}")));
			fakeStats.reply(200, "{\"players\":[]}");
		}

		assertThat(service.ask(List.of(), "Keep looking forever").complete()).isFalse();
	}

	@Test
	void returnsClaudesAnswerWhenNoToolIsNeeded() {
		fakeClaude.reply(200, claudeText("Basketball was invented in 1891."));

		String answer = ask("When was basketball invented?");

		assertThat(answer).isEqualTo("Basketball was invented in 1891.");
		assertThat(fakeClaude.requests()).hasSize(1);
		assertThat(fakeStats.requests()).isEmpty();
	}

	@Test
	void runsARequestedToolAndSendsTheResultBackToClaude() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use",
				claudeToolUse("call_1", "get_player_stats", "{\"name\":\"LeBron James\",\"season\":\"2025-26\"}")));
		fakeStats.reply(200, "{\"points_per_game\":27.5}");
		fakeClaude.reply(200, claudeText("LeBron averaged 27.5 points."));

		String answer = ask("How many points did LeBron average?");

		assertThat(answer).isEqualTo("LeBron averaged 27.5 points.");
		assertThat(fakeStats.requests()).singleElement().satisfies(request -> {
			assertThat(request.uri()).startsWith("/players/stats");
			assertThat(request.uri()).contains("season=2025-26");
		});

		// The second request must tell Claude the result and which request it answers.
		JsonNode toolResult = lastMessageOfRequest(1).get("content").get(0);
		assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
		assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("call_1");
		assertThat(toolResult.toString()).contains("27.5");
	}

	@Test
	void sendsEveryRequestAgainWithTheWholeConversation() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use",
				claudeToolUse("call_1", "search_players", "{\"name\":\"Curry\"}")));
		fakeStats.reply(200, "{\"players\":[]}");
		fakeClaude.reply(200, claudeText("Done."));

		ask("Which Currys played in the NBA?");

		// Claude has no memory, so the second request holds: question, tool request, result.
		JsonNode messages = JSON.readTree(fakeClaude.requests().get(1).body()).get("messages");
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).toString()).contains("Which Currys played in the NBA?");
		assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
		assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
	}

	@Test
	void sendsResultsOfSeveralToolsBackInOneMessage() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use",
				claudeToolUse("call_1", "get_player_stats", "{\"name\":\"Stephen Curry\"}"),
				claudeToolUse("call_2", "get_player_stats", "{\"name\":\"Kevin Durant\"}")));
		fakeStats.reply(200, "{\"points_per_game\":26.0}");
		fakeStats.reply(200, "{\"points_per_game\":25.0}");
		fakeClaude.reply(200, claudeText("Curry scored more."));

		ask("Who scored more, Curry or Durant?");

		JsonNode results = lastMessageOfRequest(1).get("content");
		assertThat(results).hasSize(2);
		assertThat(results.get(0).get("tool_use_id").asText()).isEqualTo("call_1");
		assertThat(results.get(1).get("tool_use_id").asText()).isEqualTo("call_2");
	}

	@Test
	void passesTheLeadersToolsOptionalInputsToTheStatsService() {
		fakeClaude.reply(200, claudeMessage("tool_use",
				claudeToolUse("call_1", "get_league_leaders", "{\"stat\":\"AST\",\"top_n\":3}")));
		fakeStats.reply(200, "{\"leaders\":[]}");
		fakeClaude.reply(200, claudeText("Done."));

		ask("Who are the top 3 in assists?");

		assertThat(fakeStats.requests().getFirst().uri())
				.startsWith("/leaders")
				.contains("stat=AST")
				.contains("top_n=3")
				.doesNotContain("season");
	}

	@Test
	void marksAFailedLookupAsAnErrorSoClaudeCanReact() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use",
				claudeToolUse("call_1", "get_player_stats", "{\"name\":\"Nobody\"}")));
		fakeStats.reply(404, "{\"error\":\"No player found matching 'Nobody'.\"}");
		fakeClaude.reply(200, claudeText("I couldn't find that player."));

		String answer = ask("How is Nobody playing?");

		assertThat(answer).isEqualTo("I couldn't find that player.");
		JsonNode toolResult = lastMessageOfRequest(1).get("content").get(0);
		assertThat(toolResult.get("is_error").asBoolean()).isTrue();
		assertThat(toolResult.toString()).contains("No player found");
	}

	@Test
	void marksAnUnknownToolAsAnError() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use", claudeToolUse("call_1", "make_coffee", "{}")));
		fakeClaude.reply(200, claudeText("I can't do that."));

		ask("Make me a coffee");

		assertThat(lastMessageOfRequest(1).get("content").get(0).get("is_error").asBoolean()).isTrue();
		assertThat(fakeStats.requests()).isEmpty();
	}

	@Test
	void reportsARefusalInsteadOfAnEmptyAnswer() {
		fakeClaude.reply(200, claudeMessage("refusal"));

		assertThat(ask("Something Claude declines")).isEqualTo("Claude declined to answer that question.");
	}

	@Test
	void stopsAfterTenTurnsSoAConfusedAgentCantSpendForever() {
		// A script that asks for a tool more times than the limit allows.
		for (int turn = 1; turn <= 11; turn++) {
			fakeClaude.reply(200, claudeMessage("tool_use",
					claudeToolUse("call_" + turn, "search_players", "{\"name\":\"Curry\"}")));
			fakeStats.reply(200, "{\"players\":[]}");
		}

		String answer = ask("Keep looking forever");

		assertThat(answer).contains("too many lookups");
		assertThat(fakeClaude.requests()).hasSize(10);
	}

}
