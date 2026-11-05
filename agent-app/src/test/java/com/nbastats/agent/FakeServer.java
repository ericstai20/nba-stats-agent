package com.nbastats.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny web server that lives only during a test. It sends back the replies a test
 * lines up and records every request it gets, like an actor reading from a script
 * while taking notes on what was said to them.
 *
 * Tests use it in place of Claude and the Python service, so the real HTTP code runs
 * but nothing costs money or depends on another program.
 */
class FakeServer implements AutoCloseable {

	record Reply(int status, String body) {
	}

	/** What the app sent. The URI includes the path and query, like /leaders?stat=PTS. */
	record ReceivedRequest(String uri, String body) {
	}

	private final HttpServer server;
	private final Queue<Reply> replies = new ArrayDeque<>();
	private final List<ReceivedRequest> requests = new ArrayList<>();

	FakeServer() throws IOException {
		// Port 0 asks the computer for any free port, so tests can't clash with a running app.
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Lines up the next reply. Replies go out in the order they were added. */
	void reply(int status, String body) {
		synchronized (replies) {
			replies.add(new Reply(status, body));
		}
	}

	List<ReceivedRequest> requests() {
		synchronized (replies) {
			return List.copyOf(requests);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Reply reply;
		synchronized (replies) {
			requests.add(new ReceivedRequest(exchange.getRequestURI().toString(), body));
			reply = replies.poll();
		}
		// 418 is an unusual code, so a test with too few replies fails in an obvious way.
		if (reply == null) {
			reply = new Reply(418, "{\"error\":\"The test didn't line up a reply for this request.\"}");
		}
		byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(reply.status(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	/** Runs automatically at the end of a "try" block or test, like closing a file. */
	@Override
	public void close() {
		server.stop(0);
	}

	// --- Scripted replies in the shape Claude's API uses -------------------------

	static String claudeText(String text) {
		return claudeMessage("end_turn", "{\"type\":\"text\",\"text\":\"" + text + "\"}");
	}

	static String claudeToolUse(String id, String tool, String inputJson) {
		return """
				{"type":"tool_use","id":"%s","name":"%s","input":%s}""".formatted(id, tool, inputJson);
	}

	static String claudeMessage(String stopReason, String... contentBlocks) {
		return """
				{"id":"msg_test","type":"message","role":"assistant","model":"fake-model",
				"content":[%s],"stop_reason":"%s","stop_sequence":null,
				"usage":{"input_tokens":1,"output_tokens":1}}"""
				.formatted(String.join(",", contentBlocks), stopReason);
	}

}
