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


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "realtime: run this test at time scale x1, undoing the suite's acceleration for it",
    )
    config.addinivalue_line(
        "markers",
        "slow: expensive or niche; deselected by the default target and run before a release",
    )


def pytest_addoption(parser):
    group = parser.getgroup("necesse-harness")
    group.addoption("--harness-world", default=None,
                    help="world name to use; must contain 'harness', because a run deletes it")
    group.addoption("--mod-under-test", default=None,
                    help="dev mod folder holding exactly one jar; omit to run the harness alone")
    group.addoption("--time-scale", default=None, type=float,
                    help="server speed multiplier; overrides ServerConfig.time_scale. Only applies "
                         "when --clock-ticks is given, since manual ticks ignore the clock")
    group.addoption("--clock-ticks", action="store_true", default=False,
                    help="run the world on its own clock instead of granting ticks explicitly; the "
                         "control for deciding whether manual ticking hides anything")


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
def harness_server(harness_config, request) -> HarnessServer:
    override = request.config.getoption("--time-scale")
    if override is not None:
        harness_config.time_scale = override
    if request.config.getoption("--clock-ticks"):
        harness_config.manual_ticks = False
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
    if harness_server.config.manual_ticks:
        harness.set_manual_ticks(True)
        # Manual ticks and the automatic unload sweeps do not mix: a test granting hundreds of ticks
        # crosses the sweep's thirty-one-second threshold with no wall-clock time passing, so the
        # world can be dismantled underneath a long-running session for reasons that have nothing to
        # do with what it is testing. See Unloading's own doc for the mechanism and the prior
        # real-play bug this was found from. The synthetic player has no route to the per-tick
        # keepLoaded() a real client's own region tracking would call, so the suite must suppress the
        # sweep itself rather than rely on the engine to notice it is still "there".
        harness.set_auto_unload(False)
    # Deliberately outside the manual-ticks branch, unlike the unload sweep. Autosave watches *world* time,
    # and both tick modes distort it in opposite directions: manual ticks freeze the game logic while the
    # world clock keeps running at wall-clock rate, so the save arrives on real elapsed seconds and then
    # executes on an arbitrary granted tick; clock mode scales the world delta by the time multiplier, so at
    # x20 the engine's sixty-second interval arrives in three. Both were observed -- 62s into a manual boot,
    # and 7s into an accelerated one. Either way a test ends up running against a world being saved,
    # file-system-reloaded and copied to a backup on another thread. See Autosave's own doc.
    harness.set_autosave(False)
    # One player for the session: containers are built from a player's inventory, so most verbs need
    # one, and spawning per test would be pure overhead.
    harness.spawn_player()
    try:
        yield harness
    finally:
        harness.close()


@pytest.fixture
def harness(harness_session, request) -> Harness:
    """A cleared area and an empty-handed player. The unit of isolation a test should rely on.

    Both halves were learned from a leak rather than designed. One test withdrew ten iron bars into
    the player's inventory and the next test's conservation check found fifty items where forty were
    expected, so clearing the world was not enough. The first fix -- despawn and respawn -- looked
    right and did nothing: the headless player keeps a stable authentication ID so that the server
    reuses its player file, and that is exactly what restores its inventory. A later suite caught it,
    with a crafted boat bleeding into two more tests.

    So the player's inventory is emptied explicitly, in one command, clearing everything ``query
    held`` counts.

    This is also where the ``realtime`` marker takes effect, by setting the settle acceleration to x1
    for the duration of the test. The server is only ever fast *inside* ``settle`` (see its docstring
    for why), so a marked test simply waits in real time.

    What needs it: anything asserting about real elapsed time rather than game time. A timeout measured
    in seconds cannot be stepped past, because granting ticks does not advance the wall clock -- such a
    test asks a different question under manual ticks than the one it was written to ask.

    What should *not* need it is a test that merely fails when the suite runs fast. That was the first
    approach here and it was wrong: the failures moved between runs, three or four each time, and every
    one passed in isolation. Marking them individually would have been chasing a symptom of setup no
    longer being atomic in game time. If a test needs this mark for any reason other than the wall
    clock, that is worth understanding rather than annotating.
    """
    config = harness_session.server.config
    if request.node.get_closest_marker("realtime") is not None and config.manual_ticks:
        # The world has to run on its own clock for the duration, which means both switching the model
        # and telling the server, since the mode is server-side state.
        config.manual_ticks = False
        harness_session.set_manual_ticks(False)

        def restore():
            config.manual_ticks = True
            harness_session.set_manual_ticks(True)

        request.addfinalizer(restore)

    harness_session.clear(CLEAR_RADIUS)
    harness_session.clear_player()

    # Let the world absorb the cleanup before the test starts placing things.
    #
    # Removing an object is not finished when the command returns: the engine settles object entities and
    # region state on the tick, and consumer mods invalidate their own derived state there too. Under the
    # clock this was free -- every command cost a tick, so the teardown of one test was processed during the
    # setup of the next. With game time frozen between commands that stops being true, and the symptom is
    # cross-test interference that moves around as tests are added or reordered, which reads as flakiness
    # rather than as a missing step.
    if harness_session.server.config.manual_ticks:
        harness_session.step(2)

    return harness_session
