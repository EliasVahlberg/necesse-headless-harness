"""Does the spawn region unload underneath a synthetic player, taking the terminal with it?

This tests a *mechanism*, not a failure rate, which is why it is one short run rather than many.

The hypothesis. The standing flaky symptom is always the same fact: ``terminalAt(context, 2)``
returns null for the object at spawn+(0,0), so ``query capacity`` answers -1 and every assertion
downstream reads as a wrong number. Object entities live in regions, and a region is dropped once
its ``RegionUnloadBuffer`` passes the cooldown. A *real* client pins the region it stands in --
``ServerClient.tick`` walks its own ``loadedRegions`` calling ``keepLoaded()`` every tick -- but the
harness's synthetic player has no route to that, which is why the suite suppresses the sweep at all.
So with the sweep live, nothing keeps the spawn region alive, and the terminal at spawn is exactly
what disappears.

Two arms, identical except for the switch:

* sweep suppressed  -- expect the capacity query to keep answering correctly.
* sweep live        -- expect the spawn region to unload, and the terminal to go missing.

If the second arm reproduces the symptom deterministically then the flakiness was never mysterious:
it was whether a run's timing happened to cross the cooldown before the assertion.

Run with the harness venv's python.
"""

from __future__ import annotations

from pathlib import Path

from necesse_harness import Harness, ServerConfig
from necesse_harness.process import HarnessServer

HERE = Path(__file__).resolve().parent
JAR = Path("/home/elias/Documents/my_repos/necesse-modding/arcane-storage/build/jar")

#: Comfortably past the region sweep's threshold (cooldown + 1 second, at 20 ticks a second) and past
#: the level's own. The same number the wireless-terminal pinning tests use.
TICKS = 700


def arm(harness: Harness, suppressed: bool, break_first: bool = False) -> dict:
    """Builds the smallest network that can show the symptom, then waits out the sweep."""
    harness.do("reset")
    harness.settle(2)
    harness.set_auto_unload(not suppressed)

    harness.do("place", "terminal", "0", "0")
    harness.do("place", "unit", "1", "0")
    if break_first:
        # A third member, broken before the wait. Every recorded instance of the symptom needed a break:
        # 40 isolated runs without one never failed. Breaking is also the one action that removes an
        # object entity, and removal is deferred to a tick -- so a break followed by a region unload is a
        # removal that may be pending when the region is written out.
        harness.do("place", "demonicunit", "2", "0")
    harness.settle(5)
    harness.do("fill", "1", "0", "ironbar", "40")
    harness.settle(2)

    if break_first:
        harness.do("break", "2", "0")
        harness.settle(5)

    before = harness.query("capacity", 0, 0)
    loaded_before = harness.region_loaded(0, 0)

    harness.step(TICKS)

    # Asked in this order deliberately: 'query region' does not load the region it reports on, while
    # 'query capacity' does. Reading loadedness second would show a region the previous question had
    # just brought back, which is how an unload gets mistaken for never having happened.
    loaded_after = harness.region_loaded(0, 0)
    after = harness.query("capacity", 0, 0)

    return {
        "suppressed": suppressed,
        "broke": break_first,
        "capacity_before": (before["used"], before["total"]),
        "capacity_after": (after["used"], after["total"]),
        "region_loaded_before": loaded_before,
        "region_loaded_after": loaded_after,
    }


def main() -> int:
    config = ServerConfig()
    config.world = "headless_harness_probe"
    config.mod_under_test = JAR
    server = HarnessServer(config)
    server.start()

    harness = Harness(server)
    harness.handshake()
    harness.set_manual_ticks(True)
    harness.spawn_player()

    results = []
    try:
        for suppressed, broke in ((True, False), (False, False), (True, True), (False, True)):
            results.append(arm(harness, suppressed, broke))
    finally:
        # Leave the sweep off, so teardown is not racing the thing under test.
        harness.set_auto_unload(False)
        harness.close()
        server.stop()

    print(f"\n=== after {TICKS} granted ticks, terminal at spawn+(0,0) ===")
    for r in results:
        sweep = "suppressed" if r["suppressed"] else "LIVE"
        print(f"\n sweep {sweep}, break {'yes' if r['broke'] else 'no'}:")
        print(f"   capacity before : {r['capacity_before']}")
        print(f"   capacity after  : {r['capacity_after']}")
        print(f"   region loaded   : {r['region_loaded_before']} -> {r['region_loaded_after']}")
        missing = r["capacity_after"] == (-1, -1)
        print(f"   terminal missing: {'YES -- symptom reproduced' if missing else 'no'}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
