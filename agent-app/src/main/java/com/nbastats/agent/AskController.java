package com.nbastats.agent;

import java.util.Map;

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
	record AskRequest(String question) {
	}

	record AskResponse(String answer) {
	}

	private final ClaudeService claude;

	/** Spring sees that this needs a ClaudeService and passes one in automatically. */
	public AskController(ClaudeService claude) {
		this.claude = claude;
	}

	/** POST /ask with {"question": "..."} returns {"answer": "..."}. */
	@PostMapping("/ask")
	public ResponseEntity<?> ask(@RequestBody AskRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "question: must not be blank"));
		}
		return ResponseEntity.ok(new AskResponse(claude.ask(request.question())));
	}

	/** Runs if a call to Claude fails, such as for a bad API key or a network problem. */
	@ExceptionHandler(AnthropicException.class)
	public ResponseEntity<?> handleClaudeError(AnthropicException error) {
		logger.warn("Claude request failed: {}", error.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("error", "Couldn't get an answer from Claude. Please try again shortly."));
	}

}
