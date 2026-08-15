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
