package com.nbastats.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.anthropic.errors.AnthropicException;

/**
 * The web layer: the app's front counter, like main.py in the Python service.
 * "@RestController" tells Spring that this class handles web requests and that
 * what its methods return should be sent back as JSON.
 */
@RestController
public class AskController {

	private static final Logger logger = LoggerFactory.getLogger(AskController.class);

	/** A record is a short way to write a class that just holds data. */
	record AskRequest(String question, String conversationId) {
	}

	record AskResponse(String answer, String conversationId) {
	}

	private final ClaudeService claude;
	private final ConversationStore conversations;

	/** Spring sees what this needs and passes in one of each automatically. */
	public AskController(ClaudeService claude, ConversationStore conversations) {
		this.claude = claude;
		this.conversations = conversations;
	}

	/**
	 * POST /ask with {"question": "..."} starts a conversation and returns
	 * {"answer": "...", "conversationId": "..."}. Sending that ID with the next
	 * question makes it a follow-up.
	 */
	@PostMapping("/ask")
	public ResponseEntity<?> ask(@RequestBody AskRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "question: must not be blank"));
		}

		String conversationId = request.conversationId();
		List<ConversationStore.Exchange> history = List.of();
		if (conversationId != null) {
			Optional<List<ConversationStore.Exchange>> found = conversations.find(conversationId);
			// Answering without the history could mean answering about the wrong player,
			// so say what happened instead. This happens after the app restarts.
			if (found.isEmpty()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",
						"That conversation is no longer available. Your next question will start a new one."));
			}
			history = found.get();
		}

		ClaudeService.Answer answer = claude.ask(history, request.question());
		if (answer.complete()) {
			conversationId = conversations.save(conversationId,
					new ConversationStore.Exchange(request.question(), answer.text()));
		}
		return ResponseEntity.ok(new AskResponse(answer.text(), conversationId));
	}

	/** Runs if a call to Claude fails, such as for a bad API key or a network problem. */
	@ExceptionHandler(AnthropicException.class)
	public ResponseEntity<?> handleClaudeError(AnthropicException error) {
		logger.warn("Claude request failed: {}", error.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("error", "Couldn't get an answer from Claude. Please try again shortly."));
	}

}
