"""The web layer: FastAPI routes that map URLs to functions in nba_client.py.

Think of this file as a restaurant's front counter. It takes the order (the URL),
checks that it makes sense, passes it to the kitchen (nba_client.py), and hands
back the result as JSON.

Run it from the stats-service folder with:  uvicorn main:app --port 8000
Interactive docs then appear at:            http://localhost:8000/docs
"""

from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

import nba_client

app = FastAPI(
    title="NBA Stats Service",
    description="Clean JSON stats, wrapping the nba_api package.",
)

# A season must look like 2025-26. FastAPI rejects anything else automatically.
SEASON_PATTERN = r"^\d{4}-\d{2}$"
SEASON_HELP = "Season such as 2025-26. Defaults to the current season."


# --- Error handling ----------------------------------------------------------
# An HTTP status code is a short standard signal of how a request went:
# 200 = OK, 400 = bad request, 404 = not found, 409 = conflict, 503 = temporarily
# unavailable. The handlers below let the routes stay short: when nba_client
# raises an error, the matching handler sends the response.
#
# Every error uses the same shape, {"error": "message"}, so the Java app only has
# to understand one format.


@app.exception_handler(RequestValidationError)
def handle_invalid_input(request: Request, error: RequestValidationError):
    """Replace FastAPI's detailed default format for bad input, like season=banana."""
    problems = [f"{e['loc'][-1]}: {e['msg']}" for e in error.errors()]
    return JSONResponse(status_code=400, content={"error": "; ".join(problems)})


@app.exception_handler(nba_client.PlayerNotFoundError)
def handle_player_not_found(request: Request, error: Exception):
    return JSONResponse(status_code=404, content={"error": str(error)})


@app.exception_handler(nba_client.NoDataError)
def handle_no_data(request: Request, error: Exception):
    return JSONResponse(status_code=404, content={"error": str(error)})


@app.exception_handler(nba_client.AmbiguousPlayerError)
def handle_ambiguous_player(request: Request, error: nba_client.AmbiguousPlayerError):
    return JSONResponse(
        status_code=409,
        content={"error": str(error), "candidates": error.candidates},
    )


@app.exception_handler(nba_client.NbaServiceError)
def handle_nba_service_error(request: Request, error: Exception):
    return JSONResponse(status_code=503, content={"error": str(error)})


# --- Routes ------------------------------------------------------------------
# Each decorator (the @app.get line) connects a URL to the function under it.
# Function parameters become query parameters: /leaders?stat=AST&top_n=5
#
# These are plain "def" functions on purpose. nba_api waits on the network without
# using async, so FastAPI runs each plain "def" in a separate worker thread. That
# way one slow request doesn't hold up the others.


@app.get("/players/search")
def search_players(name: str = Query(min_length=2, description="All or part of a name")):
    """Find players by name. No stats, so this doesn't call the NBA's site."""
    return {"query": name, "players": nba_client.search_players(name)}


@app.get("/players/stats")
def player_stats(
    name: str = Query(min_length=2),
    season: str | None = Query(default=None, pattern=SEASON_PATTERN, description=SEASON_HELP),
):
    """Get one player's per-game stats for a season."""
    return nba_client.get_player_stats(name, season)


@app.get("/leaders")
def leaders(
    stat: str = Query(description="One of: " + ", ".join(nba_client.LEADER_STATS)),
    season: str | None = Query(default=None, pattern=SEASON_PATTERN, description=SEASON_HELP),
    top_n: int = Query(default=10, ge=1, le=50, description="How many players, 1 to 50"),
):
    """Get the league leaders in a stat."""
    if stat.upper() not in nba_client.LEADER_STATS:
        allowed = ", ".join(nba_client.LEADER_STATS)
        return JSONResponse(
            status_code=400,
            content={"error": f"Unknown stat '{stat}'. Use one of: {allowed}"},
        )
    return nba_client.get_league_leaders(stat, season, top_n)


@app.get("/compare")
def compare(
    name_a: str = Query(min_length=2),
    name_b: str = Query(min_length=2),
    season: str | None = Query(default=None, pattern=SEASON_PATTERN, description=SEASON_HELP),
):
    """Compare two players over the same season."""
    return nba_client.compare_players(name_a, name_b, season)
