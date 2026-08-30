"""The Python equivalent of selfcheck.txt: vanilla content only, no mod under test.

It answers the question that is otherwise expensive to answer -- is the harness working in this
install, or is my mod broken -- and it keeps working when the consumer does not compile.

Deliberately not a translation of the scenario file. The scenario stays as the pasteable regression
record; these tests do the things a scenario cannot: parametrise, compare values, and name the
failure in terms of both sides.
"""

from __future__ import annotations

import pytest


def test_handshake_reports_a_vocabulary(harness):
    """Until now nothing could enumerate the harness's own verbs, which blocked any tooling."""
    vocabulary = harness.vocabulary
    assert vocabulary["protocol"] == 1
    assert "place" in vocabulary["builtins"]
    assert {"item", "total", "held"} <= set(vocabulary["queries"])


def test_a_chest_holds_what_was_put_in_it(harness):
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", 40)
    assert harness.item_at(2, 0, "ironbar") == 40


@pytest.mark.parametrize("count", [1, 7, 40])
def test_fill_is_exact_for_any_amount(harness, count):
    """A scenario file cannot do this without three copies of itself."""
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", count)
    assert harness.item_at(2, 0, "ironbar") == count


def test_quick_stack_moves_the_player_inventory_into_a_chest(harness):
    """Quick stack works on a vanilla chest because QUICK_STACK_SLOT is an engine-level convention."""
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", 1)
    harness.give("ironbar", 20)
    assert harness.held("ironbar") == 20

    harness.open(2, 0)
    harness.quickstack()
    harness.close_container()

    assert harness.held("ironbar") == 0
    assert harness.item_at(2, 0, "ironbar") == 21


def test_moving_items_around_conserves_them(harness):
    """The invariant that matters: an operation should never create or destroy items.

    ``total`` scans every inventory on the level rather than a chosen set, so an item that moved
    somewhere unexpected still counts -- which is the failure worth catching.
    """
    harness.place("storagebox", 2, 0)
    harness.place("storagebox", 4, 0)
    harness.fill(2, 0, "stone", 30)
    before = harness.total("stone")

    harness.give("stone", 10)
    harness.open(4, 0)
    harness.quickstack()
    harness.close_container()

    assert harness.total("stone") == before + 10


def test_a_failed_assertion_is_reported_as_a_failure_not_a_crash(harness):
    """The distinction the reply id exists to make: a wrong answer is not a dead server."""
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", 40)

    reply = harness.expect("item", 2, 0, "ironbar", 999)
    assert reply.ok is False
    assert reply.error is None, "a failed assertion must not look like an exception"
    assert "expected 999, found 40" in reply.failures()[0].text


def test_the_games_top_level_categories_are_what_the_source_suggested(harness):
    """Turns an inference into an observation.

    The category picker in a consumer mod is built from this tree, and the set was originally read
    off a grep of `setItemCategory` call sites -- which counts call sites, not categories. This asks
    the running game instead.
    """
    top = set(harness.query("categories")["top"])
    assert {"objects", "materials", "consumable", "equipment", "misc"} <= top
    # Small enough to present as a menu, which is what makes a dropdown over the real taxonomy
    # viable where a fixed row of icon buttons would have to invent buckets.
    assert len(top) <= 12, f"more top-level categories than a menu can carry: {sorted(top)}"


def test_an_item_belongs_to_its_category_through_its_ancestors(harness):
    """The walk a category filter performs, checked on an item whose place is not in doubt."""
    chain = harness.query("category", "ironbar")["chain"]
    assert chain[-1] == "materials", f"ironbar's top-level category is {chain[-1]}"
    assert "bars" in chain


# -- loading and unloading -------------------------------------------------------------------------
#
# The harness's own coverage of the verbs, as distinct from a consumer's use of them: that a region
# really leaves memory and really comes back, and that the flag suppresses the sweep. What a mod does
# when a region is missing is the consumer's business and is tested there.


def test_a_region_leaves_memory_and_comes_back(harness):
    """The point of the verbs: a tile's region is in memory, then is not, then is again.

    Placing a chest first, and reading it afterwards, is what distinguishes an unload from a delete.
    The engine saves a region as it drops it, so the contents have to survive the round trip -- and if
    they did not, every test built on these verbs would be testing the harness's own data loss.

    At a distance, because near the player an unload does not stick: ServerClient.tick reloads every
    region in its own set every tick. That is asserted separately below rather than worked around
    silently, since it is the first thing a test author will trip over.
    """
    dx, dy = harness.distant_offset()
    harness.load_region(dx, dy)
    harness.place("storagebox", dx, dy)
    harness.fill(dx, dy, "stone", 40)

    assert harness.region_loaded(dx, dy)
    harness.unload_region(dx, dy)
    assert not harness.region_loaded(dx, dy)

    harness.load_region(dx, dy)
    assert harness.region_loaded(dx, dy)
    assert harness.item_at(dx, dy, "stone") == 40


def test_the_players_own_region_can_be_unloaded_but_should_not_be(harness):
    """An unload near the player succeeds, and is close to useless, which is worth a test to say out loud.

    The player's own bookkeeping reloads the ground around them, so the region comes back on its own -- but
    <b>when</b> is not asserted here, and deliberately. Run alone this test saw it back within three ticks; run
    after the others it was still gone after forty. Something about session state decides it and that something
    was not identified, so the assertion stops at what held in both cases rather than encoding a number that
    happened to work once. The consequence for a test author is the same either way: if an unload needs to
    stick, work at a distance.
    """
    harness.place("storagebox", 0, 2)
    harness.unload_region(0, 2)
    assert not harness.region_loaded(0, 2)


def test_unloading_a_region_that_is_not_loaded_fails_rather_than_pretending(harness):
    dx, dy = harness.distant_offset()
    harness.load_region(dx, dy)
    harness.unload_region(dx, dy)

    reply = harness.call("unload", "region", str(dx), str(dy))
    assert not reply.ok
    assert any("no region loaded" in line for line in reply.lines)


def test_the_players_own_level_cannot_be_unloaded(harness):
    """Refused on purpose. ServerClient.tick resolves its level every tick through World.getLevel, so the
    unload would be undone immediately, and in between the player's mob belongs to an orphan."""
    identifier = harness.query("level")["identifier"]
    reply = harness.call("unload", "level", identifier)
    assert not reply.ok
    assert any("has a player on it" in line for line in reply.lines)


def test_a_region_is_sixteen_tiles_so_nearby_offsets_share_one(harness):
    """Recorded as a test because it is the fact a test author gets wrong: unloading 'the terminal's region'
    at a small offset unloads the ground the player is standing on as well."""
    near = harness.region(0, 2)
    assert near["size"] == 16
    assert (near["regionx"], near["regiony"]) == (near["playerregionx"], near["playerregiony"])

    far = harness.region(*harness.distant_offset())
    assert (far["regionx"], far["regiony"]) != (far["playerregionx"], far["playerregiony"])


def test_an_identical_sequence_consumes_an_identical_number_of_ticks(harness):
    """The lockstep guarantee, asserted rather than assumed.

    This is the property the whole manual-tick model exists to provide: the same commands must cost the
    same amount of game time, every time, so a test's outcome cannot depend on how busy the machine was.
    It holds -- measured at zero spread over five repetitions -- and it is worth a permanent guard because
    it is the one clock a test may legitimately depend on.

    The engine's *other* clocks deliberately have no such guarantee and are not asserted here:
    ``TickManager``'s counters and the world clock are derived from ``System.nanoTime()`` and advanced by
    the loop, so ``frames``, ``totalticks`` and ``worldtime`` vary run to run by design. See
    ``docs/debug-data/probe_clocks.py`` for the measurement and ``DEBUG_JOURNAL.md`` for what that costs.
    """
    def sequence() -> int:
        before = harness.query("clocks")["granted"]
        harness.place("storagebox", 2, 0)
        harness.fill(2, 0, "ironbar", 40)
        harness.settle(5)
        harness.do("break", "2", "0")
        harness.settle(5)
        return harness.query("clocks")["granted"] - before

    costs = [sequence() for _ in range(3)]
    assert len(set(costs)) == 1, f"identical work cost different amounts of game time: {costs}"


def test_a_granted_tick_is_worth_exactly_one_tick_of_time(harness):
    """The clock contract: every game-state clock advances per granted tick, not per wall-clock second.

    Manual mode originally gated ``Server.tick`` and nothing else, so three other clocks ran free and made
    identical work advance the world by different amounts. Measured over five repetitions of one command
    sequence: the world frame tick ran 27 to 78 times, the world clock moved 39 to 103ms, and the engine's own
    tick counter advanced 1 to 3 -- against a granted-tick count that was 110 every time.

    All three are now driven by the budget, so this asserts the exchange rate rather than a spread:

    * one world frame tick per granted tick -- ``WorldFrameTickPatch``
    * 50ms of world time per granted tick, the engine's own ``msPerTick`` -- ``TickManagerFullDeltaPatch``
    * one engine tick counted per granted tick -- ``TickManagerTotalTicksPatch``

    The raw loop frame count is deliberately *not* asserted. It still varies, and must: that is the unpaced
    iteration that pumps packets and drains the command queue, and freezing it would deadlock the server.
    """
    ticks = 40
    before = harness.query("clocks")
    harness.step(ticks)
    after = harness.query("clocks")

    assert after["granted"] - before["granted"] == ticks
    assert after["worldframes"] - before["worldframes"] == ticks, "world frame ticks must track granted ticks"
    assert after["totalticks"] - before["totalticks"] == ticks, "the engine's tick counter must track them too"
    assert after["worldtime"] - before["worldtime"] == ticks * 50, "50ms of world time per tick"


def test_the_clocks_query_separates_the_budget_from_the_wall_clock(harness):
    """Names the distinction in a test, so it cannot quietly stop being reported.

    Before this query the gap between granted ticks and the engine's own counters was invisible from
    Python, which is why it stayed an argument instead of a measurement for as long as it did.
    """
    clocks = harness.query("clocks")
    assert clocks["manual"] is True, "the session fixture should be in manual mode"
    assert clocks["loopseen"] is True, "the frame patch should have handed over the loop's TickManager"

    # The budget is spent, not accumulated: a granted tick is claimed by the tick that runs it.
    assert clocks["budgetleft"] == 0
    assert clocks["granted"] > 0

    # Frame ticks are not budgeted -- Server.frameTick is patched OnMethodExit, so the original runs on
    # every unpaced loop iteration. There are always more frames than the harness ever asked for.
    assert clocks["frames"] > 0


def test_the_sweep_can_be_suppressed_and_restored(harness):
    """The flag has to be visible, or a test cannot tell which regime it is running under.

    Also asserts the threshold is a sane positive number, which is the guard against the trap in
    Unloading: at Integer.MAX_VALUE the engine's own arithmetic overflows and the switch that means
    'never unload' unloads everything on the next tick.
    """
    try:
        harness.set_auto_unload(False)
        suppressed = harness.query("region", 0, 2)
        assert not suppressed["autounload"]
        assert suppressed["unloadsat"] > 20 * 60 * 60
    finally:
        harness.set_auto_unload(True)

    assert harness.query("region", 0, 2)["autounload"]
    assert harness.query("region", 0, 2)["unloadsat"] == 31 * 20

    # Put the session's own regime back. Restoring to the *engine* default above is the point of the
    # assertion, but leaving it there would hand every later test in the session a world with the sweeps
    # running -- which is precisely the contamination this switch exists to prevent.
    harness.set_auto_unload(False)


def test_autosave_can_be_suppressed_and_restored(harness):
    """Autosave is real and it lands mid-test, so the flag has to be readable like the sweep's.

    Suppressed by the fixture, because the interval is measured in world time and world time keeps
    running at wall-clock rate even while manual ticks hold the game logic still -- so any process that
    lives a minute gets a save, a file-system reload and a world copy on another thread, at a moment
    decided by nothing the test controls.
    """
    assert not harness.query("level")["autosave"], "the session fixture should suppress autosave"

    try:
        harness.set_autosave(True)
        assert harness.query("level")["autosave"]
    finally:
        harness.set_autosave(False)

    assert not harness.query("level")["autosave"]


@pytest.mark.slow
def test_the_server_side_switches_survive_a_restart(harness):
    """A fresh JVM resets them, and for a long time nothing put them back.

    This is the regression guard for a real gap rather than a hypothetical one: suppression lives in
    static state inside the server process, so every test after the first restart silently ran with the
    engine's defaults restored. Nothing reported it, and the suite still asked for suppression exactly
    once, at session start.

    Both switches are set here rather than inherited from the fixture, so this asserts what it says it
    does regardless of what ran before it in the shared session.
    """
    harness.set_auto_unload(False)
    harness.set_autosave(False)
    assert not harness.query("level")["autounload"]
    assert not harness.query("level")["autosave"]

    harness.restart()

    assert not harness.query("level")["autounload"], "restart lost the unload suppression"
    assert not harness.query("level")["autosave"], "restart lost the autosave suppression"
