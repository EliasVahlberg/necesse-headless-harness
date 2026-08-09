"""pytest fixtures. Installed as a plugin, so a consumer needs no import dance in conftest.py.

Scope maps onto cost, which is the whole design:

* ``harness_server`` is **session** scoped, because booting a JVM and generating a world is a few
  seconds and everything else here is milliseconds.
* ``harness`` is **function** scoped and clears the area around spawn between tests. That is
  weaker isolation than it looks -- ``clear`` covers a radius, not the level -- and the honest fix
  is loading a fresh level per test, which the harness cannot do yet. Until then, a test that
  places something far from spawn must clean up after itself.
"""

from __future__ import annotations

import pytest

from .client import Harness
from .process import HarnessServer, ServerConfig

#: Tiles around spawn cleared between tests. Wide enough for the scenarios in this repo.
CLEAR_RADIUS = 30


def pytest_addoption(parser):
    group = parser.getgroup("necesse-harness")
    group.addoption("--harness-world", default=None,
                    help="world name to use; must contain 'harness', because a run deletes it")
    group.addoption("--mod-under-test", default=None,
                    help="dev mod folder holding exactly one jar; omit to run the harness alone")


@pytest.fixture(scope="session")
def harness_config(request) -> ServerConfig:
    """Override this in a consumer's conftest to point at its own jar and world."""
    config = ServerConfig()
    world = request.config.getoption("--harness-world")
    mod = request.config.getoption("--mod-under-test")
    if world:
        config.world = world

    if mod:
        from pathlib import Path
        config.mod_under_test = Path(mod).resolve()

    return config


@pytest.fixture(scope="session")
def harness_server(harness_config) -> HarnessServer:
    server = HarnessServer(harness_config)
    server.start()
    try:
        yield server
    finally:
        server.stop()


@pytest.fixture(scope="session")
def harness_session(harness_server) -> Harness:
    harness = Harness(harness_server)
    harness.handshake()
    # One player for the session: containers are built from a player's inventory, so most verbs need
    # one, and spawning per test would be pure overhead.
    harness.spawn_player()
    try:
        yield harness
    finally:
        harness.close()


@pytest.fixture
def harness(harness_session) -> Harness:
    """A cleared area and a *fresh* player. The unit of isolation a test should rely on.

    The player is replaced rather than reused, and that is not tidiness. The first suite written
    against this leaked: one test withdrew ten iron bars into the player's inventory and the next
    test's conservation check found fifty items where forty were expected. Clearing the world is not
    enough, because a player carries state that no amount of clearing touches.

    Respawning costs two commands, roughly a tenth of a second, which is the cheapest correct answer
    available until the harness can load a fresh level per test.
    """
    harness_session.clear(CLEAR_RADIUS)
    harness_session.despawn_player()
    harness_session.spawn_player()
    return harness_session
