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
