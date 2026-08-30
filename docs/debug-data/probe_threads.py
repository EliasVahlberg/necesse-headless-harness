"""One-shot observability probe: what threads does a live harness server actually run?

Boots a single server the way the suite's session fixture does -- manual ticks on, unload sweeps
off, one spawned player -- and takes a ``jcmd Thread.print`` dump at three moments: after boot,
after a scenario resembling the failing ones, and after idling with no ticks granted. The point is
the *difference* between the three, since a thread that appears only in the third is one doing its
own timing.

Run with the harness venv's python. Artifacts land next to this file.
"""

from __future__ import annotations

import subprocess
import time
from pathlib import Path

from necesse_harness import Harness, ServerConfig
from necesse_harness.process import HarnessServer

HERE = Path(__file__).resolve().parent
JAR = Path("/home/elias/Documents/my_repos/necesse-modding/arcane-storage/build/jar")

#: Threads we can already account for, as name prefixes.
EXPECTED = (
    "main",                    # the game loop / server thread
    "Necesse Server",          # ditto, if named
    "ServerScanThread",        # console stdin reader
    "world-",                  # WorldEntity.executor() pool -- only ever gets System::gc
    "Thread-",                 # generic; needs its stack read to classify
    "Reference Handler", "Finalizer", "Signal Dispatcher", "Attach Listener",
    "Common-Cleaner", "Notification Thread", "process reaper",
    "JFR ", "JDWP ", "G1 ", "GC ", "VM ", "C1 ", "C2 ", "Sweeper", "Service Thread",
    "Monitor Ctrl-Break", "DestroyJavaVM",
)


def dump(pid: int, label: str) -> Path:
    out = HERE / f"threads-{label}.txt"
    result = subprocess.run(["jcmd", str(pid), "Thread.print"],
                            capture_output=True, text=True, check=False)
    out.write_text(result.stdout or result.stderr)
    return out


def thread_names(path: Path) -> list[str]:
    return [line.split('"')[1]
            for line in path.read_text(errors="replace").splitlines()
            if line.startswith('"') and '"' in line[1:]]


def report(label: str, path: Path) -> set[str]:
    found = thread_names(path)
    print(f"\n=== {label}: {len(found)} threads ({path.name}) ===")
    unexpected = [n for n in found if not n.startswith(EXPECTED)]
    for name in sorted(set(found)):
        mark = "  " if name.startswith(EXPECTED) else "??"
        print(f" {mark} {name}")
    if unexpected:
        print(f" -> {len(set(unexpected))} unaccounted-for: {sorted(set(unexpected))}")
    return set(found)


def main() -> int:
    config = ServerConfig()
    config.world = "headless_harness_probe"
    config.mod_under_test = JAR
    server = HarnessServer(config)
    server.start()
    pid = server.process.pid
    print(f"server pid {pid}")

    harness = Harness(server)
    harness.handshake()
    harness.set_manual_ticks(True)
    harness.set_auto_unload(False)
    harness.spawn_player()

    try:
        after_boot = report("after boot", dump(pid, "after-boot"))

        # The shape of the failing scenarios: place a small network, break a member, query.
        harness.do("place", "terminal", "0", "0")
        harness.do("place", "unit", "1", "0")
        harness.do("place", "demonicunit", "2", "0")
        harness.settle(5)
        harness.do("break", "2", "0")
        harness.settle(5)
        print("\ncapacity after break:", harness.query("capacity", 0, 0))
        after_scenario = report("after scenario", dump(pid, "after-scenario"))

        # Idle with *no* ticks granted. Anything that changes here is running on its own clock.
        print("\nidling 20s with zero ticks granted...")
        time.sleep(20)
        after_idle = report("after 20s idle, no ticks", dump(pid, "after-idle"))

        print("\n=== differences ===")
        print(" appeared after scenario:", sorted(after_scenario - after_boot) or "none")
        print(" appeared after idle    :", sorted(after_idle - after_scenario) or "none")
        print(" vanished after idle    :", sorted(after_scenario - after_idle) or "none")
    finally:
        harness.close()
        server.stop()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
