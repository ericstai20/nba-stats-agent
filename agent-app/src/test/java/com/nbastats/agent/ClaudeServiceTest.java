package com.nbastats.agent;

import static com.nbastats.agent.FakeServer.claudeMessage;
import static com.nbastats.agent.FakeServer.claudeText;
import static com.nbastats.agent.FakeServer.claudeToolUse;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

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

	@Test
	void returnsClaudesAnswerWhenNoToolIsNeeded() {
		fakeClaude.reply(200, claudeText("Basketball was invented in 1891."));

		String answer = service.ask("When was basketball invented?");

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

		String answer = service.ask("How many points did LeBron average?");

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

		service.ask("Which Currys played in the NBA?");

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

		service.ask("Who scored more, Curry or Durant?");

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

		service.ask("Who are the top 3 in assists?");

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

		String answer = service.ask("How is Nobody playing?");

		assertThat(answer).isEqualTo("I couldn't find that player.");
		JsonNode toolResult = lastMessageOfRequest(1).get("content").get(0);
		assertThat(toolResult.get("is_error").asBoolean()).isTrue();
		assertThat(toolResult.toString()).contains("No player found");
	}

	@Test
	void marksAnUnknownToolAsAnError() throws IOException {
		fakeClaude.reply(200, claudeMessage("tool_use", claudeToolUse("call_1", "make_coffee", "{}")));
		fakeClaude.reply(200, claudeText("I can't do that."));

		service.ask("Make me a coffee");

		assertThat(lastMessageOfRequest(1).get("content").get(0).get("is_error").asBoolean()).isTrue();
		assertThat(fakeStats.requests()).isEmpty();
	}

	@Test
	void reportsARefusalInsteadOfAnEmptyAnswer() {
		fakeClaude.reply(200, claudeMessage("refusal"));

		assertThat(service.ask("Something Claude declines")).isEqualTo("Claude declined to answer that question.");
	}

	@Test
	void stopsAfterTenTurnsSoAConfusedAgentCantSpendForever() {
		// A script that asks for a tool more times than the limit allows.
		for (int turn = 1; turn <= 11; turn++) {
			fakeClaude.reply(200, claudeMessage("tool_use",
					claudeToolUse("call_" + turn, "search_players", "{\"name\":\"Curry\"}")));
			fakeStats.reply(200, "{\"players\":[]}");
		}

		String answer = service.ask("Keep looking forever");

		assertThat(answer).contains("too many lookups");
		assertThat(fakeClaude.requests()).hasSize(10);
	}

}
