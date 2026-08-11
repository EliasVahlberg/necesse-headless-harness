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
import shutil
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

    work_dir: Path | None = None

    def resolved_work_dir(self) -> Path:
        return self.work_dir or Path(os.environ.get("HARNESS_OUT", "/tmp/necesse-harness"))


class HarnessServer:
    """One JVM, one world. Boot it once per session; it is by far the most expensive thing here."""

    def __init__(self, config: ServerConfig | None = None) -> None:
        self.config = config or ServerConfig()
        self.process: subprocess.Popen | None = None
        self._log_sink = None
        self.log_path: Path
        self.rpc_path: Path
        self._boot_time = 0.0

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
        self.log_path = out / "server.log"
        self.rpc_path = out / "replies.jsonl"

        # Keep one previous log. A run failed once here and could not be diagnosed afterwards
        # because the next run had already overwritten the evidence -- and the log is the only place
        # the reason exists, since a failure this layer cannot explain is usually explained there.
        if self.log_path.exists():
            shutil.copy2(self.log_path, self.log_path.with_suffix(".previous.log"))

        # The reply file is truncated rather than rotated: a stale reply would answer this run's
        # first request with the last run's answer, which is a bug nobody would believe. On a restart
        # it must survive instead, because the reader is already positioned in it.
        if fresh:
            self.rpc_path.write_text("")

        # The log is always truncated, including on a restart, because _await_ready looks for the
        # ready line anywhere in the file -- a leftover one from the previous boot would make a
        # restart appear instant and every command after it race the server.
        self.log_path.write_text("")

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
