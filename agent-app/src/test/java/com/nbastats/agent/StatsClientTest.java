package com.nbastats.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/** Tests how the Java app talks to the Python service, using a fake in its place. */
class StatsClientTest {

	@Test
	void encodesSpacesAndAccentsInNames() throws IOException {
		try (FakeServer fakeStats = new FakeServer()) {
			fakeStats.reply(200, "{}");

			new StatsClient(fakeStats.url()).getPlayerStats("Luka Dončić", "2025-26");

			// A URL can't hold a space or "č" directly, so each is written in a code.
			assertThat(fakeStats.requests().getFirst().uri())
					.isEqualTo("/players/stats?name=Luka%20Don%C4%8Di%C4%87&season=2025-26");
		}
	}

	@Test
	void leavesOutASeasonThatIsNull() throws IOException {
		try (FakeServer fakeStats = new FakeServer()) {
			fakeStats.reply(200, "{}");

			new StatsClient(fakeStats.url()).getPlayerStats("LeBron James", null);

			assertThat(fakeStats.requests().getFirst().uri()).doesNotContain("season");
		}
	}

	@Test
	void returnsASuccessfulBodyThatIsNotAnError() throws IOException {
		try (FakeServer fakeStats = new FakeServer()) {
			fakeStats.reply(200, "{\"players\":[]}");

			StatsClient.StatsResult result = new StatsClient(fakeStats.url()).searchPlayers("Curry");

			assertThat(result.isError()).isFalse();
			assertThat(result.body()).isEqualTo("{\"players\":[]}");
		}
	}

	@Test
	void returnsAnErrorResponseInsteadOfThrowing() throws IOException {
		try (FakeServer fakeStats = new FakeServer()) {
			fakeStats.reply(404, "{\"error\":\"No player found.\"}");

			StatsClient.StatsResult result = new StatsClient(fakeStats.url()).searchPlayers("Nobody");

			// The message has to survive, because Claude reads it to explain the problem.
			assertThat(result.isError()).isTrue();
			assertThat(result.body()).contains("No player found.");
		}
	}

	@Test
	void reportsAnErrorWhenTheServiceIsDown() throws IOException {
		String urlOfStoppedServer;
		try (FakeServer stopped = new FakeServer()) {
			urlOfStoppedServer = stopped.url();
		}

		StatsClient.StatsResult result = new StatsClient(urlOfStoppedServer).searchPlayers("Curry");

		assertThat(result.isError()).isTrue();
		assertThat(result.body()).contains("couldn't be reached");
	}

}
