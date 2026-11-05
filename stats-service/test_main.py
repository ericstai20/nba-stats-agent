"""Tests for the web layer in main.py: URLs, status codes, and the error format.

TestClient sends pretend web requests straight to the app, so no server has to run.
"""

import pytest
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_search_returns_players():
    response = client.get("/players/search", params={"name": "LeBron"})

    assert response.status_code == 200
    assert response.json()["players"][0]["name"] == "LeBron James"


def test_player_stats_returns_the_players_numbers(nba_site):
    nba_site.data = {
        "SeasonTotalsRegularSeason": [
            {
                "SEASON_ID": "2025-26",
                "TEAM_ABBREVIATION": "LAL",
                "PLAYER_AGE": 41,
                "GP": 60,
                "GS": 60,
                "MIN": 34.0,
                "PTS": 22.0,
                "REB": 6.0,
                "AST": 7.0,
                "STL": 1.0,
                "BLK": 0.5,
                "TOV": 3.0,
                "FG_PCT": 0.5,
                "FG3_PCT": 0.35,
                "FT_PCT": 0.75,
            }
        ]
    }

    response = client.get("/players/stats", params={"name": "LeBron James", "season": "2025-26"})

    assert response.status_code == 200
    assert response.json()["stats"]["points_per_game"] == 22.0


# Each row is one way a request can go wrong, and the status code it should get.
@pytest.mark.parametrize(
    "url, params, expected_status",
    [
        ("/players/stats", {"name": "LeBron James", "season": "banana"}, 400),
        ("/players/search", {"name": "L"}, 400),
        ("/leaders", {"stat": "DUNKS"}, 400),
        ("/leaders", {"stat": "PTS", "top_n": 51}, 400),
        ("/players/stats", {"name": "Zzzz Notaplayer"}, 404),
        ("/players/stats", {"name": "James"}, 409),
    ],
)
def test_bad_requests_get_the_right_status_and_error_shape(url, params, expected_status):
    response = client.get(url, params=params)

    assert response.status_code == expected_status
    assert isinstance(response.json()["error"], str)


def test_bad_season_message_is_readable():
    response = client.get("/leaders", params={"stat": "PTS", "season": "banana"})

    assert response.json()["error"] == "season: must look like 2025-26"


def test_ambiguous_name_lists_candidates():
    response = client.get("/players/stats", params={"name": "James"})

    assert len(response.json()["candidates"]) == 10


def test_season_without_stats_returns_404(nba_site):
    nba_site.data = {"SeasonTotalsRegularSeason": []}

    response = client.get("/players/stats", params={"name": "LeBron James", "season": "1990-91"})

    assert response.status_code == 404


def test_nba_site_failure_returns_503(nba_site):
    nba_site.error = TimeoutError("the site took too long")

    response = client.get("/leaders", params={"stat": "PTS"})

    assert response.status_code == 503
    assert "try again" in response.json()["error"]
