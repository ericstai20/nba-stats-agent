package com.nbastats.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Remembers recent conversations so that follow-up questions make sense. Think of a
 * coat check: the first visit gets a ticket (the conversation ID), and showing that
 * ticket later gets the right coat (the history) back.
 *
 * Everything lives in memory, so restarting the app forgets every conversation.
 */
@Service
public class ConversationStore {

	/** One question and the final answer it got. */
	record Exchange(String question, String answer) {
	}

	// Every exchange kept is sent to Claude with each new question, so this limit
	// keeps the cost of a long conversation from growing forever.
	static final int MAX_EXCHANGES_PER_CONVERSATION = 10;

	// Without this limit, the app's memory use would keep growing for as long as it runs.
	static final int MAX_CONVERSATIONS = 100;

	// "true" makes the map keep its entries ordered from least to most recently used.
	private final LinkedHashMap<String, List<Exchange>> conversations = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Exchange>> eldest) {
			return size() > MAX_CONVERSATIONS;
		}
	};

	/** Gets a conversation's history, or nothing if it isn't in the store. */
	public synchronized Optional<List<Exchange>> find(String conversationId) {
		return Optional.ofNullable(conversations.get(conversationId));
	}

	/**
	 * Saves one exchange and returns the conversation's ID. A null ID starts a new
	 * conversation with a random ID, which is too long for anyone to guess.
	 *
	 * "synchronized" lets only one web request in here at a time, because several
	 * requests changing the map at once could damage it.
	 */
	public synchronized String save(String conversationId, Exchange exchange) {
		String id = (conversationId != null) ? conversationId : UUID.randomUUID().toString();
		List<Exchange> history = new ArrayList<>(conversations.getOrDefault(id, List.of()));
		history.add(exchange);
		if (history.size() > MAX_EXCHANGES_PER_CONVERSATION) {
			history.removeFirst();
		}
		// List.copyOf makes a list that can't be changed by whoever reads it later.
		conversations.put(id, List.copyOf(history));
		return id;
	}

}
