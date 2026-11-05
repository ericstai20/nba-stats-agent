package com.nbastats.agent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Runs the agent loop: ask Claude, run any tools it requests, send back the
 * results, and repeat until it writes a final answer.
 *
 * "@Service" tells Spring to create one copy of this class when the app starts
 * and hand it to any class that needs it.
 */
@Service
public class ClaudeService {

	private static final Logger logger = LoggerFactory.getLogger(ClaudeService.class);

	private static final long MAX_TOKENS = 16000;

	// A limit on trips around the loop, so a confused agent can't keep spending
	// credits forever. Every trip is one paid request to Claude.
	private static final int MAX_TURNS = 10;

	private static final String GET_PLAYER_STATS = "get_player_stats";
	private static final String SEARCH_PLAYERS = "search_players";
	private static final String GET_LEAGUE_LEADERS = "get_league_leaders";

	private static final String SEASON_HELP = "Season such as 2025-26. Leave out for the current season.";

	// The inputs Claude sends for each tool. Optional inputs are null if left out.
	record PlayerStatsInput(String name, String season) {
	}

	record SearchPlayersInput(String name) {
	}

	// Java names don't use underscores, so @JsonProperty links topN to "top_n".
	record LeagueLeadersInput(String stat, String season, @JsonProperty("top_n") Integer topN) {
	}

	// A tool definition is a description for Claude, not code it runs. Claude reads
	// it to decide when to use the tool and what inputs to provide.
	private static final Tool PLAYER_STATS_TOOL = Tool.builder()
			.name(GET_PLAYER_STATS)
			.description("""
					Get one NBA player's regular-season per-game stats for one season: \
					games played, minutes, points, rebounds, assists, steals, blocks, \
					turnovers, and shooting percentages. If the name matches several \
					players, the error lists the possible matches.""")
			// Strict mode makes Claude's inputs follow this schema exactly.
			.strict(true)
			.inputSchema(Tool.InputSchema.builder()
					.properties(Tool.InputSchema.Properties.builder()
							.putAdditionalProperty("name", JsonValue.from(Map.of(
									"type", "string",
									"description", "The player's full name, such as LeBron James")))
							.putAdditionalProperty("season", JsonValue.from(Map.of(
									"type", "string",
									"description", SEASON_HELP)))
							.build())
					.required(List.of("name"))
					.putAdditionalProperty("additionalProperties", JsonValue.from(false))
					.build())
			.build();

	private static final Tool SEARCH_PLAYERS_TOOL = Tool.builder()
			.name(SEARCH_PLAYERS)
			.description("""
					Find NBA players, past and present, whose name contains the search \
					text. Returns names and IDs but no stats. Use this when you're unsure \
					of a player's full name or a name matched several players. The \
					is_active field comes from a list that can lag behind real rosters, \
					so treat it as a rough guide and don't state it as fact.""")
			.strict(true)
			.inputSchema(Tool.InputSchema.builder()
					.properties(Tool.InputSchema.Properties.builder()
							.putAdditionalProperty("name", JsonValue.from(Map.of(
									"type", "string",
									"description", "All or part of a name, at least 2 characters")))
							.build())
					.required(List.of("name"))
					.putAdditionalProperty("additionalProperties", JsonValue.from(false))
					.build())
			.build();

	private static final Tool LEAGUE_LEADERS_TOOL = Tool.builder()
			.name(GET_LEAGUE_LEADERS)
			.description("""
					Get the NBA's top players in one regular-season stat for one season, \
					counting only players who meet a minimum. The response states the \
					minimum that was used. Per-game stats are PTS, REB, OREB, DREB, AST, \
					STL, BLK, TOV, MIN and FG3M (three-pointers made). Shooting \
					percentages are FG_PCT, FG3_PCT and FT_PCT.""")
			.strict(true)
			.inputSchema(Tool.InputSchema.builder()
					.properties(Tool.InputSchema.Properties.builder()
							// An "enum" limits an input to a fixed list of allowed values.
							.putAdditionalProperty("stat", JsonValue.from(Map.of(
									"type", "string",
									"enum", List.of("PTS", "REB", "OREB", "DREB", "AST", "STL", "BLK",
											"TOV", "MIN", "FG3M", "FG_PCT", "FG3_PCT", "FT_PCT"))))
							.putAdditionalProperty("season", JsonValue.from(Map.of(
									"type", "string",
									"description", SEASON_HELP)))
							.putAdditionalProperty("top_n", JsonValue.from(Map.of(
									"type", "integer",
									"description", "How many players to return, 1 to 50. Defaults to 10.")))
							.build())
					.required(List.of("stat"))
					.putAdditionalProperty("additionalProperties", JsonValue.from(false))
					.build())
			.build();

	private final AnthropicClient client;
	private final String model;
	private final StatsClient stats;

	/**
	 * "@Value" fills in each setting from .env when the app starts. The text after
	 * the first colon is the default. Tests change the base URL to reach a fake Claude.
	 */
	public ClaudeService(
			@Value("${ANTHROPIC_API_KEY}") String apiKey,
			@Value("${ANTHROPIC_MODEL:claude-sonnet-5-5}") String model,
			@Value("${ANTHROPIC_BASE_URL:https://api.anthropic.com}") String baseUrl,
			StatsClient stats) {
		this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
		this.model = model;
		this.stats = stats;
	}

	/** Answers one question, looking up stats as many times as Claude needs. */
	public String ask(String question) {
		// The builder holds the whole conversation. Claude doesn't remember earlier
		// requests, so every request must include everything said so far.
		MessageCreateParams.Builder conversation = MessageCreateParams.builder()
				.model(model)
				.maxTokens(MAX_TOKENS)
				.system(systemPrompt())
				.addTool(PLAYER_STATS_TOOL)
				.addTool(SEARCH_PLAYERS_TOOL)
				.addTool(LEAGUE_LEADERS_TOOL)
				// Effort trades thoroughness against cost. Medium suits a few lookups.
				.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
				// If Claude's safety checks wrongly decline a request, let Anthropic retry
				// it on a suitable fallback model. This is a beta feature, so the SDK has
				// no named setting for it yet and we add it to the request by hand.
				.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
				.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
				.addUserMessage(question);

		for (int turn = 1; turn <= MAX_TURNS; turn++) {
			Message response = client.messages().create(conversation.build());
			StopReason stopReason = response.stopReason().orElse(null);
			logger.info("Turn {}: Claude stopped because of '{}'", turn, stopReason);

			if (StopReason.REFUSAL.equals(stopReason)) {
				return "Claude declined to answer that question.";
			}
			if (!StopReason.TOOL_USE.equals(stopReason)) {
				return textOf(response);
			}

			// Claude wants tool results, so add its message to the conversation first.
			// This keeps all of its blocks, including ones we don't read ourselves.
			conversation.addMessage(response);

			// Claude can ask for several tools at once. All the results must go back
			// together in one message.
			List<ContentBlockParam> results = new ArrayList<>();
			for (ContentBlock block : response.content()) {
				block.toolUse().ifPresent(toolUse -> results.add(runTool(toolUse)));
			}
			conversation.addUserMessageOfContentBlockParams(results);
		}

		logger.warn("Gave up after {} turns", MAX_TURNS);
		return "Sorry, that question needed too many lookups. Try asking something more specific.";
	}

	/** Claude only asks for a tool. This is the code that actually runs it. */
	private ContentBlockParam runTool(ToolUseBlock toolUse) {
		logger.info("Claude asked for {} with {}", toolUse.name(), toolUse._input());

		StatsClient.StatsResult result = switch (toolUse.name()) {
			case GET_PLAYER_STATS -> {
				PlayerStatsInput input = toolUse._input().convert(PlayerStatsInput.class);
				yield stats.getPlayerStats(input.name(), input.season());
			}
			case SEARCH_PLAYERS -> {
				SearchPlayersInput input = toolUse._input().convert(SearchPlayersInput.class);
				yield stats.searchPlayers(input.name());
			}
			case GET_LEAGUE_LEADERS -> {
				LeagueLeadersInput input = toolUse._input().convert(LeagueLeadersInput.class);
				yield stats.getLeagueLeaders(input.stat(), input.season(), input.topN());
			}
			default -> new StatsClient.StatsResult("{\"error\":\"Unknown tool.\"}", true);
		};

		// The ID tells Claude which of its requests this result answers. Errors are
		// marked so that Claude can tell them apart from real data and react.
		return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
				.toolUseId(toolUse.id())
				.content(result.body())
				.isError(result.isError())
				.build());
	}

	/** An answer is a list of "blocks". Keep only the text ones and join them. */
	private static String textOf(Message response) {
		return response.content().stream()
				.flatMap(block -> block.text().stream())
				.map(textBlock -> textBlock.text())
				.collect(Collectors.joining("\n"));
	}

	/** Claude has no clock, so it needs to be told today's date. */
	private static String systemPrompt() {
		return """
				You answer questions about the NBA. Today's date is %s.

				For any stat, use your tools instead of memory, because your memory can \
				be out of date. Base your answer only on what the tools return. If a \
				tool returns an error or doesn't have what you need, say so plainly \
				instead of guessing.

				Keep answers short and mention which season the numbers are from. Write \
				plain text without Markdown, because the web page shows your answer \
				exactly as written.
				""".formatted(LocalDate.now());
	}

}
