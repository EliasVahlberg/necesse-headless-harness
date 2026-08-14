"""The request/reply channel: an id out on stdin, a JSON line back from the reply file.

Why an id at all, given the harness worked without one: without it this is a one-way pipe. A driver
wrote a command and then searched a game log for something that looked like an answer, with no way
to know which answer belonged to which command, no values, and no way to tell a failed assertion
from a crash. Every useful thing above this layer -- real assertions, parametrised tests, a property
tester that must ask "what is the state now" between steps -- needs the reply to be attributable.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path

from .process import HarnessError, HarnessServer, ServerDied

#: Must match Harness.PROTOCOL_VERSION in the jar.
PROTOCOL_VERSION = 1


@dataclass(frozen=True)
class Check:
    ok: bool
    text: str


@dataclass(frozen=True)
class Reply:
    id: str
    ok: bool
    verb: str
    lines: tuple[str, ...]
    checks: tuple[Check, ...]
    data: dict
    error: str | None

    def failures(self) -> tuple[Check, ...]:
        return tuple(c for c in self.checks if not c.ok)

    def describe(self) -> str:
        """A failure message worth reading, which is the whole reason for the structured reply."""
        parts = [f"{self.verb} failed"]
        if self.error:
            parts.append(f"error: {self.error}")

        parts += [f"  {c.text}" for c in self.failures()]
        parts += [f"  | {line}" for line in self.lines if not line.startswith(("PASS ", "FAIL "))]
        return "\n".join(parts)


class RpcChannel:
    """Sends commands and matches replies by id.

    Replies are matched by id rather than by arrival order. Nothing currently replies out of order --
    there is exactly one emit site in the jar, in the rpc branch, so a scenario run through
    ``run`` produces one reply and not one per line -- but matching on the id is what makes that a
    property of the jar rather than an assumption this side depends on.
    """

    def __init__(self, server: HarnessServer) -> None:
        self.server = server
        self._next_id = 1
        self._pending: dict[str, Reply] = {}
        self._reader = server.rpc_path.open("r", errors="replace")
        #: Holds a partial line between reads. See _drain for why that happens.
        self._buffer = ""

    def close(self) -> None:
        try:
            self._reader.close()
        except Exception:
            pass

    def call(self, *args: str, timeout: float | None = None) -> Reply:
        for arg in args:
            if "\n" in arg or "\r" in arg:
                raise HarnessError(f"a command argument cannot contain a newline: {arg!r}")

        request_id = str(self._next_id)
        self._next_id += 1

        self.server.send_line("harness rpc " + request_id + " " + " ".join(args))
        return self._await(request_id, timeout or self.server.config.command_timeout)

    def _await(self, request_id: str, timeout: float) -> Reply:
        deadline = time.time() + timeout
        while True:
            if request_id in self._pending:
                return self._pending.pop(request_id)

            if not self._drain():
                # Checked every idle pass rather than only at the end: a dead server should be
                # reported as dead immediately, not after the full timeout.
                if self.server.process is None or self.server.process.poll() is not None:
                    raise ServerDied(
                        "the server died waiting for a reply:\n" + self.server.log_tail())

                if time.time() > deadline:
                    crash = self.server.crash_log()
                    detail = "\n\nthe game wrote a crash log during this run:\n" + crash[:4000] \
                        if crash else "\n" + self.server.log_tail()
                    raise ServerDied(
                        f"no reply to request {request_id} within {timeout}s. A deadlock does not "
                        f"stop Necesse, so this is more likely a hang than slowness.{detail}")

                # 0.5ms rather than 5ms. Once the server stopped serving commands at the tick rate, this
                # poll became the floor on command latency: measured 5.10ms per command against a server
                # answering in well under one, which is this sleep and nothing else. A suite issuing a few
                # thousand commands paid tens of seconds for it.
                time.sleep(0.0005)

    def _drain(self) -> bool:
        """Parses whatever complete replies are available. True if at least one was parsed.

        Reads into a buffer and only parses up to the last newline, because a reply can be observed
        half-written. The jar writes the JSON, then the newline, then flushes -- so a reply larger
        than the writer's buffer flushes partway through a line, and a reply carrying every line a
        scenario logged gets there easily. Parsing a fragment would fail as unparseable JSON, which
        is a confusing way to report "read too early".
        """
        chunk = self._reader.read()
        if chunk:
            self._buffer += chunk

        parsed_any = False
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            line = line.strip()
            if not line:
                continue

            try:
                raw = json.loads(line)
            except ValueError as broken:
                raise HarnessError(f"unparseable reply {line!r}: {broken}") from broken

            reply = Reply(
                id=str(raw.get("id")),
                ok=bool(raw.get("ok")),
                verb=str(raw.get("verb", "")),
                lines=tuple(raw.get("lines", ())),
                checks=tuple(Check(bool(c["ok"]), c["text"]) for c in raw.get("checks", ())),
                data=raw.get("data", {}),
                error=raw.get("error"),
            )
            self._pending[reply.id] = reply
            parsed_any = True

        return parsed_any
