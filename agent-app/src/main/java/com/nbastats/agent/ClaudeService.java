package com.nbastats.agent;

import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

/**
 * Talks to Claude. This class knows about the Claude API but nothing about the
 * web, the same split as nba_client.py and main.py in the Python service.
 *
 * "@Service" tells Spring to create one copy of this class when the app starts
 * and hand it to any class that needs it.
 */
@Service
public class ClaudeService {

	private static final long MAX_TOKENS = 16000;

	private final AnthropicClient client;
	private final String model;

	/** "@Value" fills in each setting from .env when the app starts. */
	public ClaudeService(
			@Value("${ANTHROPIC_API_KEY}") String apiKey,
			@Value("${ANTHROPIC_MODEL:claude-sonnet-5-5}") String model) {
		this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
		this.model = model;
	}

	/** Sends one question to Claude and returns the text of its answer. */
	public String ask(String question) {
		MessageCreateParams params = MessageCreateParams.builder()
				.model(model)
				.maxTokens(MAX_TOKENS)
				// Effort trades thoroughness against cost. Medium suits simple questions.
				.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
				// If Claude's safety checks wrongly decline a request, let Anthropic retry
				// it on a suitable fallback model. This is a beta feature, so the SDK has
				// no named setting for it yet and we add it to the request by hand.
				.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
				.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
				.addUserMessage(question)
				.build();

		Message response = client.messages().create(params);

		// A declined request can come back with no useful text, so check for that first.
		if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
			return "Claude declined to answer that question.";
		}

		// An answer is a list of "blocks". Keep only the text ones and join them.
		return response.content().stream()
				.flatMap(block -> block.text().stream())
				.map(textBlock -> textBlock.text())
				.collect(Collectors.joining("\n"));
	}

}
