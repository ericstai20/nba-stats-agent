package com.nbastats.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nbastats.agent.ConversationStore.Exchange;

class ConversationStoreTest {

	private final ConversationStore store = new ConversationStore();

	@Test
	void aNullIdStartsANewConversationWithItsOwnId() {
		String first = store.save(null, new Exchange("Q1", "A1"));
		String second = store.save(null, new Exchange("Q2", "A2"));

		assertThat(first).isNotEqualTo(second);
		assertThat(store.find(first)).contains(List.of(new Exchange("Q1", "A1")));
		assertThat(store.find(second)).contains(List.of(new Exchange("Q2", "A2")));
	}

	@Test
	void savingWithAnIdAddsToThatConversationInOrder() {
		String id = store.save(null, new Exchange("Q1", "A1"));

		String sameId = store.save(id, new Exchange("Q2", "A2"));

		assertThat(sameId).isEqualTo(id);
		assertThat(store.find(id)).contains(List.of(new Exchange("Q1", "A1"), new Exchange("Q2", "A2")));
	}

	@Test
	void anUnknownIdFindsNothing() {
		assertThat(store.find("no-such-conversation")).isEmpty();
	}

	@Test
	void aLongConversationKeepsOnlyTheNewestExchanges() {
		String id = null;
		int total = ConversationStore.MAX_EXCHANGES_PER_CONVERSATION + 1;
		for (int number = 1; number <= total; number++) {
			id = store.save(id, new Exchange("Q" + number, "A" + number));
		}

		List<Exchange> history = store.find(id).orElseThrow();

		assertThat(history).hasSize(ConversationStore.MAX_EXCHANGES_PER_CONVERSATION);
		assertThat(history.getFirst().question()).isEqualTo("Q2");
		assertThat(history.getLast().question()).isEqualTo("Q" + total);
	}

	@Test
	void whenFullItForgetsTheConversationUnusedForTheLongest() {
		String oldest = store.save(null, new Exchange("Q", "A"));
		String recentlyRead = store.save(null, new Exchange("Q", "A"));
		// Fill the store, leaving room for exactly one more conversation.
		for (int number = 3; number <= ConversationStore.MAX_CONVERSATIONS; number++) {
			store.save(null, new Exchange("Q", "A"));
		}

		// Reading the oldest makes it recent, so the next one in line is forgotten instead.
		store.find(oldest);
		store.save(null, new Exchange("Q", "A"));

		assertThat(store.find(oldest)).isPresent();
		assertThat(store.find(recentlyRead)).isEmpty();
	}

}
