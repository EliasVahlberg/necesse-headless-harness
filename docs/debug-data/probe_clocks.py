"""How far is the harness from real lockstep? Measured, not argued.

Manual mode gates ``Server.tick``, so *game logic* only advances when a test grants a tick. Nothing
else is gated. This probe reads every clock the server keeps, side by side, across an identical
command sequence repeated several times, and reports which of them are reproducible.

The counters, and where they come from:

* ``granted``   -- the harness's own budget. The only clock a test controls.
* ``frames``    -- ``TickManager.getTotalFrames()``. ``Server.frameTick`` is patched ``OnMethodExit``,
  so the original runs in full on every unpaced loop iteration. Expected to be large and unbounded.
* ``totalticks``/``skippedticks`` -- derived in ``TickManager.tickLogic`` from ``System.nanoTime()`` and
  advanced by the *loop*, not by ``Server.tick()``. They keep counting while manual mode skips.
* ``worldtime`` -- advanced in ``WorldEntity.tickTime`` off ``getFullDelta()``, reached only from frame
  paths, so it tracks real elapsed seconds regardless of the tick budget.

What matters is not the absolute values but the **spread across identical runs**. A counter with zero
spread is in lockstep and a test may depend on it. A counter with non-zero spread is wall-clock noise,
and anything the engine schedules off it fires at a moment no test chose -- which is the residual
non-determinism the suite has been chasing.

Run with the harness venv's python.
"""

from __future__ import annotations

import statistics
from pathlib import Path

from necesse_harness import Harness, ServerConfig
from necesse_harness.process import HarnessServer

HERE = Path(__file__).resolve().parent
JAR = Path("/home/elias/Documents/my_repos/necesse-modding/arcane-storage/build/jar")

RUNS = 5

#: Reported in this order. 'granted' first because it is the reference the rest are judged against.
COUNTERS = ("granted", "worldframes", "totalticks", "expectedticks", "skippedticks",
            "frames", "time", "worldtime")


def scenario(harness: Harness) -> dict:
    """One fixed, identical unit of work. Deterministic by construction if the harness is."""
    harness.do("reset")
    harness.settle(2)

    start = harness.query("clocks")

    harness.do("place", "terminal", "0", "0")
    harness.do("place", "unit", "1", "0")
    harness.settle(5)
    harness.do("fill", "1", "0", "ironbar", "40")
    harness.step(100)
    harness.do("break", "1", "0")
    harness.settle(5)

    end = harness.query("clocks")
    return {name: end[name] - start[name] for name in COUNTERS}


def main() -> int:
    config = ServerConfig()
    config.world = "headless_harness_probe"
    config.mod_under_test = JAR
    server = HarnessServer(config)
    server.start()

    harness = Harness(server)
    harness.handshake()
    harness.set_manual_ticks(True)
    harness.set_auto_unload(False)
    harness.set_autosave(False)
    harness.spawn_player()

    deltas = []
    try:
        for _ in range(RUNS):
            deltas.append(scenario(harness))
    finally:
        harness.close()
        server.stop()

    print(f"\n=== {RUNS} runs of an identical command sequence: delta per run ===\n")
    print(f"  {'counter':<15} {'values':<34} {'spread':>10}   verdict")
    for name in COUNTERS:
        values = [d[name] for d in deltas]
        spread = max(values) - min(values)
        shown = ", ".join(str(v) for v in values)
        if len(shown) > 32:
            shown = shown[:29] + "..."
        verdict = "lockstep" if spread == 0 else "WALL-CLOCK NOISE"
        print(f"  {name:<15} {shown:<34} {spread:>10}   {verdict}")

    granted = [d["granted"] for d in deltas]
    world = [d["worldtime"] for d in deltas]
    print(f"\n  granted ticks per run : {granted[0] if len(set(granted)) == 1 else granted}")
    print(f"  world ms per run      : min {min(world)}, max {max(world)}, "
          f"mean {statistics.mean(world):.0f}")
    if len(set(granted)) == 1 and granted[0]:
        # The core inconsistency in one number: how much game time the world clock believes has passed
        # for each tick the game logic was actually allowed to run. Lockstep would be 50ms.
        print(f"  world ms per granted tick: {statistics.mean(world) / granted[0]:.1f} "
              f"(lockstep would be 50.0)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
