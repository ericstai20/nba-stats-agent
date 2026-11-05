"""Shared test setup. pytest finds a file named conftest.py automatically.

A "fixture" is setup code that a test asks for by naming it as a parameter.
"""

from types import SimpleNamespace

import pytest

import nba_client


class FakeNbaSite:
    """Stands in for the NBA's website, like a flight simulator stands in for a plane.

    Each test says what the "site" should send back, so tests are fast, free, and
    give the same result every time, even when real stats change or the site is down.
    """

    def __init__(self):
        self.data = None
        self.error = None
        self.calls = []

    def endpoint(self, **settings):
        """Takes the place of the nba_api class that would have called the real site."""
        self.calls.append(settings)
        if self.error:
            raise self.error
        return SimpleNamespace(get_normalized_dict=lambda: self.data)


# autouse=True applies this to every test, so no test can reach the real site by accident.
@pytest.fixture(autouse=True)
def nba_site(monkeypatch):
    # monkeypatch swaps something out for one test and restores it afterward.
    site = FakeNbaSite()
    monkeypatch.setattr(nba_client.playercareerstats, "PlayerCareerStats", site.endpoint)
    monkeypatch.setattr(nba_client.leaguedashplayerstats, "LeagueDashPlayerStats", site.endpoint)
    # No real site means no need to wait 0.6 seconds between calls.
    monkeypatch.setattr(nba_client, "_wait_for_rate_limit", lambda: None)
    return site
