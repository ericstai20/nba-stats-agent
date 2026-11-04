"""Talks to nba_api and turns its large responses into small, clean dictionaries.

This file knows about basketball data but nothing about the web. main.py handles
the web part. Keeping them apart means either one can change without breaking
the other.
"""

import logging
import re
import threading
import time
import unicodedata
from datetime import date

from nba_api.stats.endpoints import leaguedashplayerstats, playercareerstats
from nba_api.stats.static import players

logger = logging.getLogger(__name__)

# Wait at least this long between calls to the NBA's site so we don't get rate limited.
SECONDS_BETWEEN_NBA_CALLS = 0.6
NBA_TIMEOUT_SECONDS = 30

# The stats the /leaders endpoint accepts: NBA's column name -> friendly label.
LEADER_STATS = {
    "PTS": "points per game",
    "REB": "rebounds per game",
    "OREB": "offensive rebounds per game",
    "DREB": "defensive rebounds per game",
    "AST": "assists per game",
    "STL": "steals per game",
    "BLK": "blocks per game",
    "TOV": "turnovers per game",
    "MIN": "minutes per game",
    "FG3M": "three-pointers made per game",
    "FG_PCT": "field goal percentage",
    "FG3_PCT": "three-point percentage",
    "FT_PCT": "free throw percentage",
}

# Without a minimum, someone who played 2 games and made 1 of 1 shots would "lead
# the league." These rules approximate the NBA's own stat-leader minimums.
MIN_GAMES_SHARE = 0.70  # must have played 70% of the games played so far
SEASON_LENGTH_GAMES = 82
# Percentage stat -> (column for shots made, shots needed over a full 82-game season)
MIN_MAKES_FOR_PERCENTAGE = {
    "FG_PCT": ("FGM", 300),
    "FG3_PCT": ("FG3M", 82),
    "FT_PCT": ("FTM", 125),
}


# --- Custom errors -----------------------------------------------------------
# Each named error describes one thing that can go wrong. main.py turns each of
# them into the right HTTP status code and a readable message.


class PlayerNotFoundError(Exception):
    """No player matches the name."""


class AmbiguousPlayerError(Exception):
    """Several players match the name, so we can't safely choose one."""

    def __init__(self, message, candidates):
        super().__init__(message)
        self.candidates = candidates


class NoDataError(Exception):
    """The player or league has no stats for that season."""


class NbaServiceError(Exception):
    """The NBA's site failed or sent back something we couldn't read."""


# --- Small helpers -----------------------------------------------------------

_rate_limit_lock = threading.Lock()
_last_call_time = 0.0


def _wait_for_rate_limit():
    """Pause if the previous NBA call was too recent.

    The lock acts like a single-file line: if two requests arrive together, they
    take turns, so the delay still applies between them.
    """
    global _last_call_time
    with _rate_limit_lock:
        wait = SECONDS_BETWEEN_NBA_CALLS - (time.monotonic() - _last_call_time)
        if wait > 0:
            time.sleep(wait)
        _last_call_time = time.monotonic()


def _call_nba(fetch):
    """Run an NBA request, converting any failure into one clear error type.

    Catching every Exception is normally too broad. Here it's deliberate: this is
    the boundary with a third-party site that can fail in many ways, such as
    timeouts, blocked requests, or an HTML page where JSON was expected.
    """
    _wait_for_rate_limit()
    try:
        return fetch()
    except Exception as error:
        logger.warning("NBA request failed: %r", error)
        raise NbaServiceError(
            "The NBA stats site didn't respond properly. Please try again shortly."
        ) from error


def current_season(today=None):
    """Work out the current season, like "2026-27", from today's date.

    NBA seasons start in October and cross into the next year, so January 2027
    still belongs to the 2026-27 season.
    """
    today = today or date.today()
    start_year = today.year if today.month >= 10 else today.year - 1
    return f"{start_year}-{(start_year + 1) % 100:02d}"


def _simplify_name(name):
    """Make names comparable: "Luka Dončić" and "luka doncic" become the same."""
    decomposed = unicodedata.normalize("NFKD", name)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold().strip()


def _to_player_summary(player):
    # is_active comes from a list built into nba_api, which can lag behind real
    # rosters. It's good enough to sort search results, but not to state as fact.
    return {
        "id": player["id"],
        "name": player["full_name"],
        "is_active": player["is_active"],
    }


# --- Player search -----------------------------------------------------------


def search_players(name):
    """Find players whose name contains the search text, active players first."""
    # find_players_by_full_name treats its input as a regular expression, a mini
    # pattern language. re.escape makes characters like "." count as plain text.
    matches = players.find_players_by_full_name(re.escape(name.strip()))
    # False sorts before True, so "not is_active" puts active players first.
    matches = sorted(matches, key=lambda p: (not p["is_active"], p["full_name"]))
    return [_to_player_summary(p) for p in matches]


def _find_one_player(name):
    """Turn a name into exactly one player, or raise a helpful error."""
    matches = players.find_players_by_full_name(re.escape(name.strip()))
    if not matches:
        raise PlayerNotFoundError(f"No player found matching '{name}'.")

    # A full-name match beats partial ones: "Gary Payton" also matches "Gary Payton II".
    exact = [p for p in matches if _simplify_name(p["full_name"]) == _simplify_name(name)]
    pool = exact or matches
    if len(pool) == 1:
        return pool[0]

    # Different players can share an exact name, so prefer the only active one.
    if exact:
        active = [p for p in exact if p["is_active"]]
        if len(active) == 1:
            return active[0]

    pool = sorted(pool, key=lambda p: (not p["is_active"], p["full_name"]))
    raise AmbiguousPlayerError(
        f"'{name}' matches {len(pool)} players. Use a full name.",
        candidates=[_to_player_summary(p) for p in pool[:10]],
    )


# --- Player stats ------------------------------------------------------------


def _clean_player_row(row, teams):
    """Keep only the key columns and give them clear names."""
    return {
        "season": row["SEASON_ID"],
        "teams": teams,
        "age": row["PLAYER_AGE"],
        "games_played": row["GP"],
        "games_started": row["GS"],
        "minutes_per_game": row["MIN"],
        "points_per_game": row["PTS"],
        "rebounds_per_game": row["REB"],
        "assists_per_game": row["AST"],
        "steals_per_game": row["STL"],
        "blocks_per_game": row["BLK"],
        "turnovers_per_game": row["TOV"],
        "field_goal_pct": row["FG_PCT"],
        "three_point_pct": row["FG3_PCT"],
        "free_throw_pct": row["FT_PCT"],
    }


def get_player_stats(name, season=None):
    """Get one player's regular-season per-game stats for a season."""
    season = season or current_season()
    player = _find_one_player(name)

    data = _call_nba(
        lambda: playercareerstats.PlayerCareerStats(
            player_id=player["id"], per_mode36="PerGame", timeout=NBA_TIMEOUT_SECONDS
        ).get_normalized_dict()
    )
    rows = [r for r in data["SeasonTotalsRegularSeason"] if r["SEASON_ID"] == season]
    if not rows:
        raise NoDataError(f"{player['full_name']} has no regular-season stats for {season}.")

    # A player traded mid-season gets one row per team, plus a combined row marked
    # "TOT" (total). The combined row is the one that describes their whole season.
    combined = next((r for r in rows if r["TEAM_ABBREVIATION"] == "TOT"), None)
    teams = [r["TEAM_ABBREVIATION"] for r in rows if r["TEAM_ABBREVIATION"] != "TOT"]
    return {
        "player": player["full_name"],
        "player_id": player["id"],
        "stats": _clean_player_row(combined or rows[-1], teams),
    }


def compare_players(name_a, name_b, season=None):
    """Get two players' stats for the same season, side by side."""
    season = season or current_season()
    return {
        "season": season,
        "player_a": get_player_stats(name_a, season),
        "player_b": get_player_stats(name_b, season),
    }


# --- League leaders ----------------------------------------------------------


def get_league_leaders(stat, season=None, top_n=10):
    """Get the top players in a stat, using only players who meet a minimum."""
    season = season or current_season()
    stat = stat.upper()

    data = _call_nba(
        lambda: leaguedashplayerstats.LeagueDashPlayerStats(
            season=season,
            per_mode_detailed="PerGame",
            season_type_all_star="Regular Season",
            timeout=NBA_TIMEOUT_SECONDS,
        ).get_normalized_dict()
    )
    rows = data["LeagueDashPlayerStats"]
    if not rows:
        raise NoDataError(f"No league stats are available for {season}.")

    # The most games any player has played stands in for "games played so far."
    games_so_far = max(r["GP"] for r in rows)

    if stat in MIN_MAKES_FOR_PERCENTAGE:
        made_column, full_season_makes = MIN_MAKES_FOR_PERCENTAGE[stat]
        needed = full_season_makes * games_so_far / SEASON_LENGTH_GAMES
        qualified = [r for r in rows if r["GP"] * r[made_column] >= needed]
        qualification = f"at least about {round(needed)} total {made_column} so far"
    else:
        needed = MIN_GAMES_SHARE * games_so_far
        qualified = [r for r in rows if r["GP"] >= needed]
        qualification = f"at least {round(needed)} games played"

    qualified.sort(key=lambda r: r[stat], reverse=True)

    leaders = []
    for position, row in enumerate(qualified[:top_n], start=1):
        # Tied players share a rank instead of one being shown ahead of the other.
        tied = leaders and leaders[-1]["value"] == row[stat]
        leaders.append(
            {
                "rank": leaders[-1]["rank"] if tied else position,
                "player": row["PLAYER_NAME"],
                "team": row["TEAM_ABBREVIATION"],
                "games_played": row["GP"],
                "value": row[stat],
            }
        )

    return {
        "season": season,
        "stat": stat,
        "stat_description": LEADER_STATS[stat],
        "qualification": qualification,
        "leaders": leaders,
    }
