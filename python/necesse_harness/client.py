"""The verb surface, as Python.

Two deliberate choices worth knowing before writing tests against this:

* **A verb that fails raises; a query returns a value.** Placing an object that cannot be placed is
  a broken test, not an interesting result, so it raises immediately with the server's own lines
  attached. Assertions belong in the test, written as plain ``assert`` so pytest can show both
  sides -- which is the entire reason to be in Python rather than in a scenario file.
* **``expect`` is still here.** It asserts in-game and is redundant next to ``query``, but a
  scenario line can be pasted into a live server to watch a failure happen by hand, and a JSON
  reply cannot. When a Python test fails, ``Harness.as_scenario`` prints the equivalent lines.
"""

from __future__ import annotations

import time

from .process import HarnessError, HarnessServer
from .rpc import PROTOCOL_VERSION, Reply, RpcChannel


class Harness:
    """A live harness. Get one from the ``harness`` fixture rather than building it yourself."""

    def __init__(self, server: HarnessServer) -> None:
        self.server = server
        self.rpc = RpcChannel(server)
        self._history: list[str] = []
        self.vocabulary: dict = {}

    # -- plumbing -------------------------------------------------------------------------------

    def handshake(self) -> dict:
        """Checks the jar speaks our protocol, and records what vocabulary it has.

        Refusing a mismatch rather than coping with one: a client misreading replies from a jar it
        does not match would produce failures that look like mod bugs. This project has already
        loaded a stale installed jar once.
        """
        reply = self.call("hello")
        protocol = reply.data.get("protocol")
        if protocol != PROTOCOL_VERSION:
            raise HarnessError(
                f"the installed harness speaks protocol {protocol}, this client speaks "
                f"{PROTOCOL_VERSION}. Update whichever is older; the jar and this package are "
                "released together from one repo so they are meant to match")

        self.vocabulary = reply.data
        return reply.data

    def call(self, *args, timeout: float | None = None) -> Reply:
        """Runs a verb and returns the reply without judging it.

        Arguments are coerced to strings, because everything on the wire is a word anyway and a test
        reads better as ``do("bench", 0, 0, 64, 200)`` than with str() around every coordinate. Query
        already accepted numbers, so this removes an inconsistency rather than adding leniency.

        ``timeout`` overrides the configured per-command limit, for the few verbs whose work is
        unbounded by nature -- running a few thousand game ticks is one command but a lot of work.
        """
        words = [str(arg) for arg in args]
        self._history.append(" ".join(words))
        return self.rpc.call(*words, timeout=timeout)

    def do(self, *args: str, timeout: float | None = None) -> Reply:
        """Runs a verb and raises unless it succeeded."""
        reply = self.call(*args, timeout=timeout)
        if not reply.ok:
            raise HarnessError(reply.describe() + "\n\nas a scenario:\n" + self.as_scenario())

        return reply

    #: How many commands a failure message reproduces. The session fixture keeps one harness for the
    #: whole run, so the untruncated history would be hundreds of lines in every error -- and the
    #: lines that explain a failure are the ones just before it.
    SCENARIO_TAIL = 15

    def as_scenario(self, limit: int = SCENARIO_TAIL) -> str:
        """The last few commands, as scenario lines that can be pasted into a live server."""
        recent = self._history[-limit:]
        lines = [f"harness {line}" for line in recent]
        if len(self._history) > limit:
            lines.insert(0, f"# ... {len(self._history) - limit} earlier commands omitted")

        return "\n".join(lines)

    def tick(self) -> int:
        """The server tick count for the level under test."""
        return int(self.query("tick")["tick"])

    def set_time_scale(self, multiplier: float) -> None:
        """Sets how fast the server runs, as a multiplier on its 20 ticks a second.

        See ``ServerConfig.time_scale`` for why this matters more than anything else here. Applied once
        per session by the plugin, so a test only calls this to override it -- typically down to 1.0 to
        reproduce something that only happens at real speed.
        """
        self.do("timescale", str(multiplier))

    def set_manual_ticks(self, manual: bool) -> None:
        """Detaches game time from the wall clock, or reattaches it.

        In manual mode the world advances only when :meth:`step` grants it. See ``ManualTicks`` on the Java
        side for the measurements behind this; the short version is that a headless suite spends nearly all
        its time waiting, and granting ticks is both faster and more deterministic than waiting for them.
        """
        if manual:
            self.do("ticks", "manual", str(self.server.config.manual_fps))
        else:
            self.do("ticks", "auto")

    def step(self, ticks: int = 1, timeout: float = 120.0) -> int:
        """Runs `ticks` game ticks and returns how many actually passed.

        One command, no polling. The server runs the ticks inside the verb, which is the whole reason this
        is fast: the first version handed the loop a budget and polled for completion, and since each poll
        is itself a command served on the server thread, the polling competed with the loop it was waiting
        for -- 6.4ms per tick, nearly all of it the client's own round trips.

        The returned count is read back from the server rather than assumed, because "how much game time
        passed" is the one thing a test using this actually depends on.

        The timeout is generous because a single call can legitimately ask for thousands of ticks, and every
        one of them is real work the server has to do.
        """
        before = self.tick()
        self.do("tick", str(ticks), timeout=timeout)
        return self.tick() - before

    def settle(self, ticks: int = 40, timeout: float = 30.0, accelerate: bool = True) -> int:
        """Let `ticks` server ticks pass, and return how many actually did.

        Time passing is what makes anything with a timer, a queue or a cascade testable. Without it a test
        can only call the work directly, which verifies the arithmetic and silently skips the scheduling.

        **How the ticks are obtained depends on the execution model, and the difference is worth knowing
        because it changes what the test is asserting.** Under manual ticks -- the default -- this grants
        exactly `ticks` and returns when they are spent, so the test gets the game time it asked for and no
        more. Under the clock it polls until that many have gone by at the server's own rate, so the test
        also gets however much wall time that took, and anything else in the world gets to act during it.

        Manual is both faster and stricter, and the strictness is the point. Two earlier attempts at speed
        are recorded in ``ManualTicks`` because they failed instructively: running the whole session at x20
        cut 333 seconds to 48 and made the suite flaky, because setup had been relying on a fixture's
        commands fitting inside a single tick, and marking the flaky tests individually was chasing that
        symptom rather than the cause.

        ``accelerate=False``, or the ``realtime`` marker, forces the clock. Use it when the question
        involves real elapsed time rather than game time -- a timeout measured in seconds cannot be
        fast-forwarded, and stepping past it would not answer the question the test is asking.
        """
        if accelerate and self.server.config.manual_ticks:
            return self.step(ticks, timeout=timeout)

        scale = self.server.config.time_scale if accelerate else 1.0
        if scale != 1.0:
            self.set_time_scale(scale)
        try:
            start = self.tick()
            deadline = time.monotonic() + timeout
            while True:
                passed = self.tick() - start
                if passed >= ticks:
                    return passed
                if time.monotonic() > deadline:
                    raise TimeoutError(
                        f"only {passed} of {ticks} ticks passed in {timeout}s -- is the server still "
                        f"ticking? (time scale x{scale})"
                    )
                time.sleep(0.002)
        finally:
            if scale != 1.0:
                self.set_time_scale(1.0)

    def restart(self) -> None:
        """Restarts the server on the same world and reconnects the player.

        For persistence: the stop saves the world and the boot loads it back, so anything asserted
        after this call is being read from disk rather than from memory.

        The player is re-spawned because a new process has none. Its authentication is stable, so the
        server reuses its player file -- which means the player's own inventory persists too, and a
        test asserting on held items after a restart is asserting about the save, not about a fresh
        character.

        **Manual tick mode does not survive the restart and is re-established here.** It is server-side
        state, so a fresh JVM starts on the clock; without this every test after a restart would silently
        change execution model. An earlier version of this method also dropped the time scale around the
        save, to work around a bus network coming back as ``nobus`` after a reload -- that failure was a
        symptom of running the whole session accelerated, and it went away once time stopped running
        between commands at all.
        """
        # Auto first, for the same reason close() does it: the stop inside restart saves the world, and
        # saving needs ticks. Frozen, the restart would cost the full kill timeout instead of a clean save,
        # which would also make this the one method that cannot test persistence.
        if self.server.config.manual_ticks:
            self.set_manual_ticks(False)

        self.server.restart()
        self.spawn_player()
        if self.server.config.manual_ticks:
            self.set_manual_ticks(True)

    def close(self) -> None:
        """Releases the connection, returning the world to its own clock first.

        **The clock has to come back before the server is asked to stop**, and the cost of forgetting is
        specific: a clean shutdown saves the world and disconnects clients, and that work happens on game
        ticks. With ticks frozen the server accepts ``stop`` and then makes no progress, so the client waits
        out its full 45-second kill timeout on every session. Measured exactly that, as a 45.36s teardown
        against a 3.10s boot -- the entire fixed cost of a run, hidden in the one phase nobody watches.
        """
        try:
            if self.server.config.manual_ticks:
                self.set_manual_ticks(False)
        except Exception:
            # Best effort. A server already gone cannot be handed its clock back, and failing here would
            # replace a real test result with a teardown error.
            pass

        self.rpc.close()

    # -- world ----------------------------------------------------------------------------------

    def place(self, obj: str, dx: int, dy: int) -> Reply:
        return self.do("place", obj, str(dx), str(dy))

    def break_at(self, dx: int, dy: int) -> Reply:
        return self.do("break", str(dx), str(dy))

    def clear(self, radius: int, tile: str | None = None) -> Reply:
        return self.do("clear", str(radius), *( [tile] if tile else [] ))

    def fill(self, dx: int, dy: int, item: str, count: int) -> Reply:
        return self.do("fill", str(dx), str(dy), item, str(count))

    # -- player and containers ------------------------------------------------------------------

    def spawn_player(self) -> Reply:
        return self.do("player", "spawn")

    def despawn_player(self) -> Reply:
        return self.do("player", "despawn")

    def clear_player(self) -> Reply:
        """Empties the player's hands. Not the same as respawning, which restores the saved file."""
        return self.do("player", "clear")

    def give(self, item: str, count: int) -> Reply:
        return self.do("give", item, str(count))

    def open(self, dx: int, dy: int) -> Reply:
        return self.do("open", str(dx), str(dy))

    def close_container(self) -> Reply:
        return self.do("close")

    def click(self, slot: int, action: str) -> Reply:
        return self.do("click", str(slot), action)

    def quickstack(self) -> Reply:
        return self.do("quickstack")

    def restock(self) -> Reply:
        return self.do("restock")

    def run_scenario(self, name: str) -> Reply:
        """Runs an existing scenario file. Reuse rather than rewrite: the .txt files still earn their keep."""
        return self.do("run", name)

    # -- values ---------------------------------------------------------------------------------

    def query(self, kind: str, *args) -> dict:
        return self.do("query", kind, *(str(a) for a in args)).data

    def item_at(self, dx: int, dy: int, item: str) -> int:
        return self.query("item", dx, dy, item)["count"]

    def total(self, item: str) -> int:
        """Every inventory on the level. The conservation check: items should not appear or vanish."""
        return self.query("total", item)["count"]

    def held(self, item: str) -> int:
        return self.query("held", item)["count"]

    # -- loading -----------------------------------------------------------------------------------
    #
    # Necesse drops what nobody is near, on a thirty-second timer, and object entities live in regions rather
    # than in levels -- so a chest can be absent from memory while its level is fully loaded. A suite that runs
    # in milliseconds never sees that happen, which is how the first consumer shipped a wireless terminal that
    # pinned the level and not the region: only a human playing the game could reproduce it. These make the
    # timer's outcome reachable on demand.

    def unload_region(self, dx: int, dy: int) -> Reply:
        """Drop the region holding a tile, saving it, as the engine's own sweep would."""
        return self.do("unload", "region", str(dx), str(dy))

    def load_region(self, dx: int, dy: int) -> Reply:
        """Load the region holding a tile, synchronously. Generates it if there is no save file."""
        return self.do("load", "region", str(dx), str(dy))

    def region(self, dx: int, dy: int) -> dict:
        """Loaded state, unload buffer, and region coordinates -- the last so a test can tell whether an offset
        actually crosses a boundary. A region is 16 tiles, so nearby offsets share the player's own."""
        return self.query("region", dx, dy)

    def region_loaded(self, dx: int, dy: int) -> bool:
        return self.region(dx, dy)["loaded"]

    def distant_offset(self, regions: int = 6) -> tuple[int, int]:
        """An offset far enough from the player to be in another region, and still inside the level.

        Needed because a region is only 16 tiles and the player's client keeps its own nearby regions loaded --
        ServerClient.tick calls getRegion(..., load) for every region in its set every tick, so unloading one
        near the player is undone before the next command arrives. A test that wants an unload to stick has to
        work at a distance, and the distance depends on the level's size, so it cannot be a constant.
        """
        level = self.query("level")
        spawn_x, spawn_y = level["spawnx"], level["spawny"]
        reach = regions * 16

        def pick(spawn: int, extent: int) -> int:
            if extent <= 0:
                return spawn + reach
            if spawn + reach < extent - 8:
                return spawn + reach
            return max(8, spawn - reach)

        return pick(spawn_x, level["tilewidth"]) - spawn_x, pick(spawn_y, level["tileheight"]) - spawn_y

    def set_auto_unload(self, automatic: bool) -> Reply:
        """The engine's unload sweeps, or neither.

        Turn them off around a test that grants hundreds of ticks: in manual mode the sweep's thirty-one seconds
        pass in no wall-clock time, so a long test can have its world dismantled for reasons unrelated to what it
        is testing. On is the engine's behaviour and the default.
        """
        return self.do("autounload", "on" if automatic else "off")

    def expect(self, kind: str, *args) -> Reply:
        """The in-game assertion, kept for parity with scenarios. Prefer query plus assert."""
        return self.call("expect", kind, *(str(a) for a in args))
