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

    def call(self, *args) -> Reply:
        """Runs a verb and returns the reply without judging it.

        Arguments are coerced to strings, because everything on the wire is a word anyway and a test
        reads better as ``do("bench", 0, 0, 64, 200)`` than with str() around every coordinate. Query
        already accepted numbers, so this removes an inconsistency rather than adding leniency.
        """
        words = [str(arg) for arg in args]
        self._history.append(" ".join(words))
        return self.rpc.call(*words)

    def do(self, *args: str) -> Reply:
        """Runs a verb and raises unless it succeeded."""
        reply = self.call(*args)
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

    def restart(self) -> None:
        """Restarts the server on the same world and reconnects the player.

        For persistence: the stop saves the world and the boot loads it back, so anything asserted
        after this call is being read from disk rather than from memory.

        The player is re-spawned because a new process has none. Its authentication is stable, so the
        server reuses its player file -- which means the player's own inventory persists too, and a
        test asserting on held items after a restart is asserting about the save, not about a fresh
        character.
        """
        self.server.restart()
        self.spawn_player()

    def close(self) -> None:
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

    def expect(self, kind: str, *args) -> Reply:
        """The in-game assertion, kept for parity with scenarios. Prefer query plus assert."""
        return self.call("expect", kind, *(str(a) for a in args))
