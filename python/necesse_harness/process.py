"""Booting and stopping a real Necesse dedicated server.

The awkward parts of this file are all lessons from the bash runner, and each one cost a debugging
session to learn:

* **stdout goes to a file, never a pipe.** A pipe nobody drains fills its buffer and blocks the
  writer -- the server -- forever, and the symptom is a server that mysteriously stops responding
  mid-run. Replies arrive on their own channel, so there is no reason to read stdout at all except
  to diagnose a failure.
* **A run must be able to give up.** A deadlock does not stop Necesse: the engine's
  ``ThreadFreezeMonitor`` writes a crash log and leaves the JVM alive with its command thread no
  longer reading stdin. Anything waiting on the process waits forever, and a wrapper timeout turns
  that into a fake duration -- one hang was reported as a 400-second test run, which was just the
  timeout that happened to be wrapped around it.
* **Commands are fed only after the server says it started.** Fed earlier, the console scanner
  consumes them before the world exists and they are silently lost.
* **``-hiddencheats`` is mandatory.** Without it every command answers "Running this command will
  disable achievements. Run it again to accept this." and does nothing.
"""

from __future__ import annotations

import os
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_GAME_DIR = Path.home() / ".steam/debian-installation/steamapps/common/Necesse"
DEFAULT_APPDATA = Path.home() / ".config/Necesse"

READY_LINE = "Started server"


class HarnessError(RuntimeError):
    """Anything wrong with the harness or the server, as opposed to a failed assertion."""


class ServerDied(HarnessError):
    pass


@dataclass
class ServerConfig:
    """Where things are. Every field has an environment variable so a consumer needs no code."""

    game_dir: Path = field(default_factory=lambda: Path(
        os.environ.get("NECESSE_GAME_DIR", DEFAULT_GAME_DIR)))

    appdata: Path = field(default_factory=lambda: Path(
        os.environ.get("NECESSE_APPDATA", DEFAULT_APPDATA)))

    #: The dev mod folder holding exactly one jar, or None to run the harness by itself. None is a
    #: legitimate case: it is how the harness checks whether it works in this install at all.
    mod_under_test: Path | None = field(default_factory=lambda: (
        Path(os.environ["MOD_UNDER_TEST"]) if os.environ.get("MOD_UNDER_TEST") else None))

    world: str = field(default_factory=lambda: os.environ.get("HARNESS_WORLD", "headless_harness_py"))

    #: Seconds to wait for the world to generate and the server to report itself started.
    boot_timeout: float = 120.0

    #: Seconds to wait for any single command's reply.
    command_timeout: float = 30.0

    #: How fast the server runs **while a test is waiting in** ``Harness.settle``, as a multiplier on
    #: its 20 ticks a second. Outside settle the server always runs at x1.
    #:
    #: This is the one knob that changes suite runtime by minutes rather than seconds. Anything with a
    #: timer, a queue or a cascade can only be tested by letting ticks pass, and at x1 those waits are
    #: real seconds: the first consumer spent 186 of its 333 seconds asleep, waiting out 3713 ticks.
    #:
    #: Raising it is not a trick played on the engine -- ``TickManager.globalTimeMod`` is the game's own
    #: fast-forward, driven from a debug key in ``MainMenu`` and ``MainGame``. Game time per tick is
    #: unchanged; the ticks simply arrive sooner. Measured on a small headless world it scales linearly
    #: well past where it was expected to plateau: x10 gave 200 ticks a second, x20 gave 400, x100 gave
    #: 2000.
    #:
    #: **Why only inside settle**, which is worth stating because running the whole session fast is the
    #: obvious thing to try and it does not work: every harness command is a round trip, so at x1 a
    #: fixture placing seven objects fits inside about one tick, and at x20 it spans a few hundred. The
    #: devices under test then start working on a half-built network, and the suite goes flaky in a way
    #: that moves between runs. Confining the speed to the one method whose job is waiting keeps setup
    #: atomic and still collects almost all of the saving.
    #:
    #: Set to 1.0 to reproduce anything that only misbehaves at real speed.
    time_scale: float = float(os.environ.get("HARNESS_TIME_SCALE", "20"))

    #: Whether game time is detached from the wall clock, so tests grant ticks instead of waiting.
    #:
    #: This is the default execution model and the one to prefer. It is faster -- a granted tick costs
    #: microseconds where a waited one costs a fiftieth of a second -- and, more importantly, it is
    #: deterministic: nothing ticks between a test's commands, so setup cannot be raced by the systems
    #: under test. ``time_scale`` only applies when this is off.
    #:
    #: Turn it off to run the world on its own clock, which is what a test asserting about real elapsed
    #: time needs. The ``realtime`` pytest marker does that per test.
    manual_ticks: bool = os.environ.get("HARNESS_MANUAL_TICKS", "1") not in ("0", "false", "False")

    #: Frames a second while manual ticks are in force.
    #:
    #: The frame is where queued verbs are drained, so this is the ceiling on command latency: at the
    #: server's usual twenty it is 50ms, which is what a command cost before any of this. Higher is faster
    #: but runs the engine's own per-frame work -- packet processing, movement integration -- more often
    #: than it was written for, so it is exposed rather than fixed.
    manual_fps: int = int(os.environ.get("HARNESS_MANUAL_FPS", "1000"))

    work_dir: Path | None = None

    #: How many boots' logs to keep before the oldest are pruned. A `pytest-all` run is four boots,
    #: so this is roughly the last fifteen runs.
    keep_logs: int = int(os.environ.get("HARNESS_KEEP_LOGS", "60"))

    def resolved_work_dir(self) -> Path:
        # Deliberately not /tmp, which is where this used to point. A flaky suite is diagnosed from the
        # log of the run that failed, and on this machine /tmp is cleared often enough that the evidence
        # for an intermittent failure was routinely gone before anyone went looking for it.
        #
        # Per world, so two projects do not share a directory. They did, and it cost a misdiagnosis: with
        # an arcane-production suite running alongside an arcane-storage one, "the newest log" was the
        # other project's, and log pruning counted both projects' boots against one budget. A world name
        # identifies the project as well as anything available here and stays stable across runs, which a
        # pid would not.
        default = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "necesse-harness"
        base = self.work_dir or Path(os.environ.get("HARNESS_OUT", default))
        return base / self.world


class HarnessServer:
    """One JVM, one world. Boot it once per session; it is by far the most expensive thing here."""

    def __init__(self, config: ServerConfig | None = None) -> None:
        self.config = config or ServerConfig()
        self.process: subprocess.Popen | None = None
        self._log_sink = None
        self.log_path: Path
        self.rpc_path: Path
        self._boot_time = 0.0
        # Identifies this session's logs, so the four boots of a pytest-all run group together and do
        # not collide with a concurrent one.
        self._run_id = f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}"
        self._boot_seq = 0

    # -- guards ---------------------------------------------------------------------------------

    def _check_world_name(self) -> None:
        # A fresh run deletes the world it is given, and a world someone has played in is real work.
        # Refusing by name is crude and has already prevented one accident.
        if "harness" not in self.config.world:
            raise HarnessError(
                f"refusing to use world {self.config.world!r}: the name must contain 'harness', "
                "because starting a run deletes it")

    def _check_installed_jar(self) -> None:
        """The server loads the *installed* harness, not the one you just built.

        Getting this wrong presents as a verb the harness "does not have", with a usage line from
        whichever build happens to be installed -- which is a confusing way to spend ten minutes.
        """
        installed = sorted((self.config.appdata / "mods").glob("NecesseHeadlessHarness-*.jar"))
        if not installed:
            raise HarnessError(
                f"the harness is not installed in {self.config.appdata / 'mods'}. Run 'make install' "
                "in the harness repo: the game accepts one dev mod and the mod under test needs it")

        # Only meaningful when running from a source checkout; a wheel install has no build folder.
        built = sorted((Path(__file__).resolve().parents[2] / "build/jar").glob(
            "NecesseHeadlessHarness-*.jar"))
        if built and built[-1].stat().st_mtime > installed[-1].stat().st_mtime:
            raise HarnessError(
                f"{built[-1].name} in build/jar is newer than the installed copy. Run 'make install' "
                "in the harness repo, or the server will load the old one")

    def _check_mod_enabled(self) -> None:
        """The installed harness can be present and switched off.

        The game keeps an enable flag per mod in ``mods/modlist.data``, and a mod it has just
        discovered is written there as ``enabled = false``. The dedicated server reads the same
        file, so it reports "Found mod: Necesse Headless Harness" and then does not load it -- and
        the mod under test dies with ``NoClassDefFoundError`` on a harness class, which reads as a
        broken consumer rather than a disabled dependency.

        Found the honest way: launching the game client once was enough to disable it.
        """
        modlist = self.config.appdata / "mods/modlist.data"
        if not modlist.exists():
            return

        text = modlist.read_text(errors="replace")
        marker = "elias.necesseheadlessharness"
        if marker not in text:
            return

        entry = text[text.index(marker):]
        entry = entry[:entry.index("}")] if "}" in entry else entry
        if "enabled = false" in entry:
            raise HarnessError(
                f"the harness is installed but disabled in {modlist}. Enable it in the game's Mods "
                "menu, or set 'enabled = true' for elias.necesseheadlessharness. The game disables "
                "newly discovered mods by default, so launching the client once can cause this")

    # -- lifecycle ------------------------------------------------------------------------------

    def start(self, fresh: bool = True) -> None:
        """Boots the server.

        ``fresh=False`` keeps the existing world and the existing reply file, which is what a restart
        needs: the world is the state under test, and the reply reader holds an open position in the
        file, so truncating it would make every later answer invisible.
        """
        self._check_world_name()
        self._check_installed_jar()
        self._check_mod_enabled()

        server_jar = self.config.game_dir / "Server.jar"
        java = self.config.game_dir / "jre/bin/java"
        if not server_jar.exists():
            raise HarnessError(f"no Server.jar in {self.config.game_dir}")

        out = self.config.resolved_work_dir()
        out.mkdir(parents=True, exist_ok=True)

        # One reply file per run, named after the run rather than shared.
        #
        # It used to be a single 'replies.jsonl' under a work directory shared by every project on the
        # machine, truncated on each fresh boot. Two suites running at once therefore fought over one file:
        # the second one's start truncated the first one's replies *while its reader was positioned in the
        # file*, so the first run waited forever for an answer that had been deleted. Worse, the truncation
        # happens here, before the JVM tries to bind its port -- so a second launch that fails outright with
        # "Address already in use" still corrupts the run that was already going. Observed exactly that way:
        # an arcane-production suite starting mid-run took an arcane-storage suite down with it, and the
        # BindException in the *new* log was the only visible symptom.
        #
        # A per-run name removes the whole class of problem rather than narrowing it. Nothing is shared, so
        # nothing needs truncating, and a stale reply cannot be read by a run that did not write it.
        self.rpc_path = out / f"replies-{self._run_id}.jsonl"

        # A log per boot, rather than one file rotated once.
        #
        # Two reasons, both learned from a failure that could not be explained afterwards. A restart is a
        # boot, so a pytest-all run is *four* servers: keeping only the current log and one previous threw
        # away half of every run, and the failures being chased occur in the earlier processes as often as
        # the later ones. And a fresh file per boot is what _await_ready needs anyway -- it looks for the
        # ready line anywhere in the file, so a leftover one from the previous boot would make a restart
        # appear instant and every command after it race the server. Rotating by name gets that for free
        # instead of by truncating, which is what destroyed the evidence.
        logs = out / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        self._boot_seq += 1
        self.log_path = logs / f"server-{self._run_id}-{self._boot_seq}.log"
        self._prune_logs(logs)

        # A stable name for "the log I care about right now", so anything reporting a path can point at
        # something a human can find twice.
        latest = out / "server-latest.log"
        try:
            if latest.is_symlink() or latest.exists():
                latest.unlink()
            latest.symlink_to(self.log_path)
        except OSError:
            # A symlink is a convenience, never a requirement.
            pass

        # The reply file belongs to this run alone, so there is nothing stale to clear -- but it is created
        # empty on the first boot because the reader opens it and expects to find it. On a restart it must
        # survive untouched, since the reader is already positioned in it.
        if fresh:
            self.rpc_path.write_text("")
            self._prune_replies(out)

        if fresh:
            world_file = self.config.appdata / "saves/worlds" / f"{self.config.world}.zip"
            if world_file.exists():
                world_file.unlink()

        command = [
            str(java) if java.exists() else "java",
            f"-Dnecesseheadlessharness.rpc={self.rpc_path}",
            "-jar", "Server.jar",
            "-nogui",
            # Without this every command answers with the achievements warning and does nothing.
            "-hiddencheats",
            "-world", self.config.world,
        ]
        if self.config.mod_under_test:
            command += ["-mod", f"{self.config.mod_under_test}/"]

        self._boot_time = time.time()
        # Held so stop() can close it. Left to the garbage collector it survives as long as the
        # object does, which for a session-scoped fixture is the whole run.
        self._log_sink = self.log_path.open("wb")
        self.process = subprocess.Popen(
            command,
            cwd=self.config.game_dir,
            stdin=subprocess.PIPE,
            # A file, not a pipe: see the module docstring.
            stdout=self._log_sink,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )

        self._await_ready()

    def _prune_logs(self, logs: Path) -> None:
        """Keeps the newest ``keep_logs`` boot logs, so history is bounded rather than unbounded."""
        try:
            existing = sorted(logs.glob("server-*.log"), key=lambda p: p.stat().st_mtime)
        except OSError:
            return

        for stale in existing[: max(0, len(existing) - self.config.keep_logs)]:
            try:
                stale.unlink()
            except OSError:
                pass

    def _prune_replies(self, out: Path) -> None:
        """Keeps reply files bounded, since each run now leaves its own behind.

        Deliberately never touches the file this run owns, and tolerates another run's file disappearing
        underneath it: two suites may legitimately be pruning the same directory at the same time.
        """
        try:
            existing = sorted(out.glob("replies-*.jsonl"), key=lambda p: p.stat().st_mtime)
        except OSError:
            return

        keep = max(1, self.config.keep_logs)
        for stale in existing[: max(0, len(existing) - keep)]:
            if stale == self.rpc_path:
                continue
            try:
                stale.unlink()
            except OSError:
                pass

    def _await_ready(self) -> None:
        deadline = time.time() + self.config.boot_timeout
        while time.time() < deadline:
            if READY_LINE in self.log_path.read_text(errors="replace"):
                return

            if self.process.poll() is not None:
                raise ServerDied(
                    "the server exited before it started:\n" + self.log_tail())

            time.sleep(0.25)

        self.stop()
        raise ServerDied(
            f"the server did not report {READY_LINE!r} within {self.config.boot_timeout}s:\n"
            + self.log_tail())

    def send_line(self, line: str) -> None:
        if self.process is None or self.process.poll() is not None:
            raise ServerDied("the server is not running:\n" + self.log_tail())

        self.process.stdin.write(line + "\n")
        self.process.stdin.flush()

    def restart(self) -> None:
        """Stops the server and boots it again on the same world.

        The stop is a clean one, so the world is saved on the way down; this is therefore the only
        way to test that anything survives a save and a load. Costs one boot, which is by far the
        most expensive thing in the suite -- so tests that need it should be few and say why.
        """
        self.stop()
        self.start(fresh=False)

    def stop(self) -> None:
        if self.process is None:
            return

        if self.process.poll() is None:
            try:
                self.send_line("stop")
            except Exception:
                pass

            try:
                # Bounded, because a deadlocked server never answers 'stop' and would hang here.
                self.process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()

        if self.process.stdin:
            self.process.stdin.close()

        if self._log_sink is not None:
            self._log_sink.close()
            self._log_sink = None

        self.process = None

    # -- diagnosis ------------------------------------------------------------------------------

    def log_tail(self, lines: int = 40) -> str:
        try:
            return "\n".join(self.log_path.read_text(errors="replace").splitlines()[-lines:])
        except Exception:
            return "(no log)"

    def crash_log(self) -> str | None:
        """The crash log, if the game wrote one during this run.

        Worth surfacing rather than leaving on disk: when the server deadlocks, this file already
        names both threads and the locks each holds, and nothing reads it.
        """
        path = self.config.game_dir / "latest-crash.log"
        if path.exists() and path.stat().st_mtime >= self._boot_time:
            return path.read_text(errors="replace")

        return None
