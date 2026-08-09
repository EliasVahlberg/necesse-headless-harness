"""The reply reader, tested without a game.

Everything else in this repo needs a licensed install and a few seconds of world generation. This
does not: the protocol is a file of JSON lines, and reading it is ordinary code with ordinary edge
cases. It is also the only test here that could run on a hosted CI runner.

The case that matters is a half-written line. The jar writes the JSON, then the newline, then
flushes, so a reply bigger than the writer's buffer is observable in pieces -- and a reply carrying
every line a scenario logged is easily that big. The reader used to hand fragments straight to
json.loads and report "unparseable reply", which blames the data for a read that happened too early.
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import pytest

from necesse_harness.process import ServerDied
from necesse_harness.rpc import RpcChannel


@dataclass
class FakeConfig:
    command_timeout: float = 2.0


class FakeProcess:
    """A process that is running. poll() returning None is how subprocess says "still alive"."""

    returncode = None

    def poll(self):
        return None


class FakeServer:
    """Just enough of HarnessServer: a reply file, a command sink, and a liveness answer."""

    def __init__(self, tmp_path: Path) -> None:
        self.rpc_path = tmp_path / "replies.jsonl"
        self.rpc_path.write_text("")
        self.log_path = tmp_path / "server.log"
        self.log_path.write_text("")
        self.config = FakeConfig()
        self.sent: list[str] = []
        self.process = FakeProcess()

    def send_line(self, line: str) -> None:
        self.sent.append(line)

    def log_tail(self, lines: int = 40) -> str:
        return ""

    def crash_log(self):
        return None

    def append(self, text: str) -> None:
        with self.rpc_path.open("a") as sink:
            sink.write(text)
            sink.flush()


def reply_json(request_id: str, **extra) -> str:
    body = {"id": request_id, "ok": True, "verb": "query", "lines": [], "checks": [], **extra}
    return json.dumps(body)


@pytest.fixture
def server(tmp_path) -> FakeServer:
    return FakeServer(tmp_path)


def test_a_reply_is_matched_to_its_request(server):
    channel = RpcChannel(server)
    threading.Timer(0.05, lambda: server.append(reply_json("1") + "\n")).start()

    reply = channel.call("query", "total", "ironbar")
    assert reply.id == "1"
    assert server.sent == ["harness rpc 1 query total ironbar"]


def test_a_half_written_line_is_not_parsed_until_it_is_whole(server):
    """The regression. Splitting inside the JSON is what a mid-line flush looks like."""
    line = reply_json("1", data={"count": 40})
    head, tail = line[:20], line[20:]
    server.append(head)

    channel = RpcChannel(server)
    threading.Timer(0.05, lambda: server.append(tail + "\n")).start()

    reply = channel.call("query", "total", "ironbar")
    assert reply.data == {"count": 40}


def test_replies_are_matched_by_id_not_by_arrival_order(server):
    """Nothing replies out of order today. This keeps that a property of the jar, not a dependency."""
    channel = RpcChannel(server)
    server.append(reply_json("2", data={"second": True}) + "\n")
    server.append(reply_json("1", data={"first": True}) + "\n")

    first = channel.call("query", "total", "ironbar")
    second = channel.call("query", "total", "stone")
    assert first.data == {"first": True}
    assert second.data == {"second": True}


def test_a_missing_reply_times_out_and_says_it_is_probably_a_hang(server):
    channel = RpcChannel(server)
    server.config.command_timeout = 0.2

    started = time.time()
    with pytest.raises(ServerDied, match="more likely a hang"):
        channel.call("query", "total", "ironbar")

    # Bounded by the timeout, not by anything slower. A wrapper that cannot give up is how a
    # deadlock gets mistaken for a slow run.
    assert time.time() - started < 2.0


def test_a_newline_in_an_argument_is_refused_before_it_is_sent(server):
    """One command per line is the whole framing, so an argument containing a newline is two commands."""
    channel = RpcChannel(server)
    with pytest.raises(Exception, match="newline"):
        channel.call("echo", "one\ntwo")

    assert server.sent == []
