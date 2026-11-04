package com.nbastats.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Python stats service over HTTP. Java and Python can work together
 * because they only exchange web requests and JSON, so neither needs to know
 * what language the other is written in.
 */
@Service
public class StatsClient {

	private static final Logger logger = LoggerFactory.getLogger(StatsClient.class);

	/** What came back from the stats service, and whether it was an error. */
	record StatsResult(String body, boolean isError) {
	}

	private final RestClient restClient;

	public StatsClient(@Value("${STATS_SERVICE_URL:http://localhost:8000}") String baseUrl) {
		// Without time limits, a stuck request could leave the app waiting forever.
		SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
		timeouts.setConnectTimeout(Duration.ofSeconds(5));
		timeouts.setReadTimeout(Duration.ofSeconds(90));
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
	}

	/** Gets one player's stats. A null season means the current season. */
	public StatsResult getPlayerStats(String name, String season) {
		Map<String, String> params = new HashMap<>();
		params.put("name", name);
		if (season != null) {
			params.put("season", season);
		}
		return get("/players/stats", params);
	}

	/**
	 * Sends a GET request. Error responses are returned rather than thrown because
	 * messages like "No player found" are useful for Claude to read.
	 */
	private StatsResult get(String path, Map<String, String> params) {
		try {
			return restClient.get()
					.uri(uri -> {
						uri.path(path);
						// "{name}" is a placeholder. Filling it in through build() safely
						// encodes spaces and accents, as in "Luka Dončić".
						params.keySet().forEach(key -> uri.queryParam(key, "{" + key + "}"));
						return uri.build(params);
					})
					.exchange((request, response) -> new StatsResult(
							Objects.requireNonNullElse(response.bodyTo(String.class), ""),
							response.getStatusCode().isError()));
		}
		catch (RestClientException error) {
			logger.warn("Stats service request failed: {}", error.getMessage());
			return new StatsResult("{\"error\":\"The stats service couldn't be reached.\"}", true);
		}
	}

}
