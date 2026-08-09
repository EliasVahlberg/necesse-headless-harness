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

    def call(self, *args: str) -> Reply:
        """Runs a verb and returns the reply without judging it."""
        self._history.append(" ".join(args))
        return self.rpc.call(*args)

    def do(self, *args: str) -> Reply:
        """Runs a verb and raises unless it succeeded."""
        reply = self.call(*args)
        if not reply.ok:
            raise HarnessError(reply.describe() + "\n\nas a scenario:\n" + self.as_scenario())

        return reply

    def as_scenario(self) -> str:
        """Everything sent so far, as scenario lines that can be pasted into a live server."""
        return "\n".join(f"harness {line}" for line in self._history)

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
