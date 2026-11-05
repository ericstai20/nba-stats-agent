"""Tests for the basketball logic in nba_client.py.

Most tests follow the same three steps:
  1. Arrange: set up the situation, such as what the fake NBA site will return.
  2. Act: call the function being tested.
  3. Assert: check the result. If an assert is false, the test fails.

Player names come from the list built into nba_api, which is stored on this computer
and pinned to one version, so using it doesn't touch the network.
"""

from datetime import date

import pytest

import nba_client


def career_row(season="2025-26", team="LAL", **stats):
    """One row of NBA career data. Tests only need to set the values they care about."""
    row = {
        "SEASON_ID": season,
        "TEAM_ABBREVIATION": team,
        "PLAYER_AGE": 30,
        "GP": 70,
        "GS": 70,
        "MIN": 35.0,
        "PTS": 25.0,
        "REB": 5.0,
        "AST": 5.0,
        "STL": 1.0,
        "BLK": 1.0,
        "TOV": 2.0,
        "FG_PCT": 0.5,
        "FG3_PCT": 0.4,
        "FT_PCT": 0.8,
    }
    return row | stats


def league_row(name, games=82, **stats):
    """One player's row in the league-wide data."""
    row = {"PLAYER_NAME": name, "TEAM_ABBREVIATION": "LAL", "GP": games, "PTS": 10.0}
    return row | stats


# --- current_season ----------------------------------------------------------


# parametrize runs the same test once for each row of inputs.
@pytest.mark.parametrize(
    "today, expected",
    [
        (date(2026, 9, 30), "2025-26"),  # the last day before a new season
        (date(2026, 10, 1), "2026-27"),  # the first day of a new season
        (date(2028, 3, 15), "2027-28"),  # the second half of a season
        (date(1999, 11, 1), "1999-00"),  # the short year must keep two digits
    ],
)
def test_current_season(today, expected):
    assert nba_client.current_season(today) == expected


# --- Finding players ---------------------------------------------------------


def test_simplify_name_ignores_accents_and_capitals():
    assert nba_client._simplify_name("  Luka Dončić ") == "luka doncic"


def test_search_lists_active_players_first():
    results = nba_client.search_players("James")
    is_active = [player["is_active"] for player in results]
    # Sorting puts True before False, which is the order active-first results must have.
    assert is_active == sorted(is_active, reverse=True)
    assert any(player["name"] == "LeBron James" for player in results)


def test_search_treats_a_dot_as_plain_text():
    # In a regular expression "." means "any character", so "J.J" would match "JaJuan".
    names = [player["name"] for player in nba_client.search_players("J.J")]
    assert names
    assert all("j.j" in name.lower() for name in names)


def test_exact_name_beats_a_longer_name():
    # "Gary Payton" is also part of "Gary Payton II", but this should not be a conflict.
    assert nba_client._find_one_player("Gary Payton")["full_name"] == "Gary Payton"


def test_finding_a_player_works_without_accents():
    assert nba_client._find_one_player("Luka Doncic")["full_name"] == "Luka Dončić"


def test_unknown_player_raises_not_found():
    with pytest.raises(nba_client.PlayerNotFoundError):
        nba_client._find_one_player("Zzzz Notaplayer")


def test_vague_name_raises_ambiguous_with_at_most_ten_candidates():
    with pytest.raises(nba_client.AmbiguousPlayerError) as caught:
        nba_client._find_one_player("James")
    assert len(caught.value.candidates) == 10


# --- Player stats ------------------------------------------------------------


def test_player_stats_returns_clean_names(nba_site):
    nba_site.data = {"SeasonTotalsRegularSeason": [career_row(PTS=27.5)]}

    result = nba_client.get_player_stats("LeBron James", "2025-26")

    assert result["player"] == "LeBron James"
    assert result["stats"]["points_per_game"] == 27.5
    assert result["stats"]["teams"] == ["LAL"]


def test_traded_player_uses_the_combined_row(nba_site):
    # TOT isn't last here, so code that just takes the final row would fail this test.
    nba_site.data = {
        "SeasonTotalsRegularSeason": [
            career_row(team="DAL", PTS=10.0),
            career_row(team="TOT", PTS=20.0),
            career_row(team="LAL", PTS=30.0),
        ]
    }

    stats = nba_client.get_player_stats("LeBron James", "2025-26")["stats"]

    assert stats["points_per_game"] == 20.0
    assert stats["teams"] == ["DAL", "LAL"]


def test_only_the_requested_season_is_used(nba_site):
    nba_site.data = {
        "SeasonTotalsRegularSeason": [
            career_row(season="2024-25", PTS=11.0),
            career_row(season="2025-26", PTS=22.0),
        ]
    }

    stats = nba_client.get_player_stats("LeBron James", "2024-25")["stats"]

    assert stats["points_per_game"] == 11.0


def test_season_the_player_missed_raises_no_data(nba_site):
    nba_site.data = {"SeasonTotalsRegularSeason": [career_row(season="2024-25")]}

    with pytest.raises(nba_client.NoDataError):
        nba_client.get_player_stats("LeBron James", "2025-26")


def test_site_failure_becomes_a_clear_error(nba_site):
    nba_site.error = TimeoutError("the site took too long")

    with pytest.raises(nba_client.NbaServiceError):
        nba_client.get_player_stats("LeBron James", "2025-26")


# --- League leaders ----------------------------------------------------------


def leaders_of(nba_site, rows, stat="PTS", top_n=10):
    nba_site.data = {"LeagueDashPlayerStats": rows}
    return nba_client.get_league_leaders(stat, "2025-26", top_n)


def test_leaders_are_sorted_best_first_and_respect_top_n(nba_site):
    rows = [
        league_row("Third", PTS=10.0),
        league_row("First", PTS=30.0),
        league_row("Second", PTS=20.0),
    ]

    leaders = leaders_of(nba_site, rows, top_n=2)["leaders"]

    assert [leader["player"] for leader in leaders] == ["First", "Second"]


def test_leaders_minimum_rounds_up_to_whole_games(nba_site):
    # 70% of 82 games is 57.4, so 58 games qualifies and 57 doesn't.
    rows = [
        league_row("Full season", games=82, PTS=10.0),
        league_row("Just enough", games=58, PTS=20.0),
        league_row("One short", games=57, PTS=30.0),
    ]

    result = leaders_of(nba_site, rows)

    assert [leader["player"] for leader in result["leaders"]] == ["Just enough", "Full season"]
    assert result["qualification"] == "at least 58 games played"


def test_percentage_leaders_need_enough_makes_instead_of_games(nba_site):
    # A full season needs 82 threes. Per-game makes times games gives the total.
    rows = [
        league_row("Most games", games=82, FG3M=2.0, FG3_PCT=0.40),
        league_row("Enough threes", games=41, FG3M=2.0, FG3_PCT=0.45),
        league_row("Too few threes", games=82, FG3M=0.5, FG3_PCT=0.90),
    ]

    leaders = leaders_of(nba_site, rows, stat="FG3_PCT")["leaders"]

    assert [leader["player"] for leader in leaders] == ["Enough threes", "Most games"]


def test_tied_players_share_a_rank(nba_site):
    rows = [
        league_row("First", PTS=30.0),
        league_row("Tied A", PTS=20.0),
        league_row("Tied B", PTS=20.0),
        league_row("Fourth", PTS=10.0),
    ]

    leaders = leaders_of(nba_site, rows)["leaders"]

    # After two players tied for 2nd, the next player is 4th, like in a real standings table.
    assert [leader["rank"] for leader in leaders] == [1, 2, 2, 4]


def test_leaders_accepts_lowercase_stat(nba_site):
    assert leaders_of(nba_site, [league_row("Only")], stat="pts")["stat"] == "PTS"


def test_season_with_no_league_data_raises_no_data(nba_site):
    with pytest.raises(nba_client.NoDataError):
        leaders_of(nba_site, [])
