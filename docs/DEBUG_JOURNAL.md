# Debugging journal — harness non-determinism

Append-only. One or two sentences per investigation, plus what it rules **out**, because the
expensive mistake is re-checking something already checked. Raw data goes in `docs/debug-data/`
and is referenced by filename.

Format: `## <date> — <question>` then **Finding**, **Rules out**, **Artifact**.

---

## Standing symptom

`make pytest-all` fails a shifting set of tests in roughly 1 run in 10–15. Every failure resolves
to the same fact: a query at a tile that should hold an object finds nothing, which the mod's verbs
report as the `-1` sentinel (`assert -1 == 40`) or an empty name. Not a hang, not a crash, ~50s
whether it passes or fails. Filed as `necesse-headless-harness` issue #2.

Deterministic collection order splits a `pytest-all` run into **four server processes**, cut by the
three `slow` restart tests:

| Process | Range (alphabetical) |
|---|---|
| 1 | `test_bus_apply` → `test_bus_states.py:165` |
| 2 | rest of `test_bus_states` → `test_buses.py:225` |
| 3 | rest of `test_buses` → `test_persistence.py:22` |
| 4 | rest of `test_persistence` → `test_wireless_terminal` |

Observed failures land in processes 1, 3 and 4 — so it is **not** confined to one process, and it
occurs before any restart has happened. Failure counts varied per run (3, 8, 9), which suggests each
process is independently at risk rather than one run-wide poisoning.

---

## 2026-08-29 — Two real bugs found and fixed (neither explains the standing symptom)

**Finding.** (a) The automatic region/level unload sweeps were live under manual ticks, where the
sweep's ~31s threshold passes in no wall-clock time; the session fixture now calls
`set_auto_unload(False)`. (b) `NetworkIndexes.share()` in arcane-storage leaked an orphaned index
whenever a network's lowest-tile-order member changed, since the old key was never removed.

**Rules out.** Both are real and worth keeping, but the standing symptom reproduced unchanged with
both in place, so neither is its cause.

---

## 2026-08-29 — Ruled out by direct measurement or reading

**Finding.** None of the following is the cause:

- **Region/level unloading at the failing tile.** At the moment of failure `query region` reports
  `loaded: True`, `query level` reports `unloadbuffer: 0`, and `autounload: False`.
- **Placement, or the demonic tier's registration.** The same placement with **no break** never
  failed in 40 isolated runs; the break is required to trigger it.
- **Drain margin around `reset`.** Raising the fixture's `settle(2)` either side of `reset` to
  `settle(5)` changed nothing (still 2 failures in 15 runs).
- **The command dispatch / tick-granting mechanism.** `ServerThreadTasks.runAndWait` blocks the
  caller on a `CountDownLatch` until the work completes on the server thread; `tick`/`settle` grant
  and spend synchronously. No race found in it.
- **`NetworkIndexes` being on the failing path at all.** Capacity/station queries go
  `terminalAt` → `getLinkedUnits()` → `UnitNetwork.discover`, a fresh walk with no caching.
- **A cascade from the broken tile.** The mod's objects define no `isValid`/`checkAround` override
  and no `ObjectDestroyedListener`, so breaking (2,0) has no engine path to (0,0).
- **`TileEntityList.frameTick` purging entities.** It only moves entities; the removal sweep exists
  only in `serverTick`/`clientTick`, so entity removal *is* inside the tick budget.
- **`worldEntity.executor()`.** The engine's only submissions to it are `System::gc`.

**Rejected hypothesis, for the record.** A subagent proposed that `TileEntityList.serverTick`'s
collect-then-remove sweep (`map.remove(uniqueID)`, key-only, no identity check — TileEntityList.java
~330) removes a freshly-placed entity. The pattern is genuinely unsafe-looking, but the whole method
body holds `manager.lock` and `addHidden`'s `map.compute` needs the same lock, so no interleaving is
possible. Not disproven as a latent hazard; disproven as *this* mechanism.

---

## 2026-08-30 — Q3: does anything advance state on real time, outside the tick budget?

**Finding — yes, world time does.** `ServerTickPatch` gates `Server.tick()` (all game logic), but
`ServerFrameTickPatch` is an `OnMethodExit` advice, so `Server.frameTick()` runs **in full, every
loop iteration, unpaced and ungated**. `Server.frameTick` (Server.java:274) processes every pending
packet and then calls `world.frameTick` → `WorldEntity.serverFrameTick` → `tickTime`, and `tickTime`
(WorldEntity.java:628, called only from lines 544/571 — both frame paths, never `serverTick`)
advances `this.time` and `this.worldTime` by `tickManager.getFullDelta()`, which
`TickManager` computes from `System.nanoTime()`.

So **manual ticks freeze game logic but not the world clock**: world time keeps running at wall-clock
rate while the world is supposedly stopped. The harness patches only `Server.tick`,
`Server.frameTick` and `Level.serverTick`, and does nothing about this.

**Consequence not yet confirmed to fire (next step).** `Server.tickAutoSave` (Server.java:639) is
gated on `saveTime <= worldEntity.getTime()` with `saveTime = getTime() + 60_000` — i.e. **60s of
real time**. Its condition therefore becomes true on the wall clock, and it then executes on the next
*granted* tick, i.e. mid-test at an unpredictable point. Every 15th autosave additionally calls
`reloadFileSystem()` and spawns a backup thread. A region read during that window is a plausible
route to "the tile reads as empty", which is the standing symptom. Unconfirmed: whether a ~50s suite
divided over four processes ever reaches the 60s threshold in any one process.

**Rules out.** Region/level *unload* sweeps as a real-time bypass: both are inside `Server.tick`
(lines 349–375), so they are budgeted.

**Artifact.** none (source reading only).

---

## 2026-08-30 — Q1: thread inventory of a live harness server

**Finding.** 58 threads, of which 8 are game/harness rather than JVM infrastructure. All are
accounted for, and **none appears or disappears** across a scenario or across 20s of idling with
zero ticks granted (only JIT compiler threads churn):

| Thread | What it is | Runs off the tick budget? |
|---|---|---|
| `Server Thread` | the game loop (`ServerTickThread.run` → `GameLoop.runMainGameLoop`) | it *is* the budget |
| `Command scanner` | harness stdin reader | yes, but marshals via `ServerThreadTasks` |
| `Server Socket`, `Server LAN Socket` | engine networking accept loops | yes |
| `Thread Freeze Monitor` | engine deadlock detector | yes, benign |
| `level-SERVER-surface-light-update` | `LightManager:51` single-thread executor; reads regions through `RegionBoundsExecutor` | **yes** |
| `level-SERVER-surface-executor-N` | `Level.executor()` (Level.java:1595), used by `FutureAITask` for mob AI | **yes** |
| `world-…-executor-N` | `WorldEntity.executor()` — only ever receives `System::gc` | yes, benign |

**Rules out.** No hidden thread is spawned per operation, and no autosave backup thread appeared in
~40s — consistent with autosave's 60s threshold not being reached. The light thread is the only
off-tick actor that touches level state, and `WORKFLOW.md` already records a `LightManager`
lock-order inversion, so it stays a suspect for *hangs* but there is no evidence it removes objects.

**Artifact.** `docs/debug-data/probe_threads.py` and `threads-{after-boot,after-scenario,after-idle}.txt`.

---

## 2026-08-30 — Q4: the engine's tick counters are wall-clock, so "every N ticks" work is decoupled

**Finding — this is a genuine lockstep break, independent of the standing symptom.**
`TickManager.tickLogic()` derives `gameTick`, `tick` (0–19, reset every real second) and `totalTicks`
from `System.nanoTime()`, at 20/s of **real** time. It is the loop that advances them, not
`Server.tick()`. Two consequences under manual ticks:

1. The loop keeps advancing those counters at 20/s regardless of the budget, while `ServerTickPatch`
   skips the ungranted ticks. So the counters and the granted ticks are **two different clocks**.
2. The `tick` verb calls `Server.tick()` directly N times in a row, so the counters do not advance
   *at all* across a burst — and `Server.frameTick` does not run between them either, meaning no
   packet processing and no movement integration mid-`settle`, unlike the engine's real loop.

Engine logic scheduled off those counters therefore fires a wrong number of times — plausibly zero,
plausibly on every tick of a burst, depending only on where the wall clock happens to be. This is
widespread, and it reaches object entities, not just cosmetics: `EntityManager:523` (mob despawn roll,
`getTick() == 1`), `HomestoneObjectEntity:24`, `MusicPlayerObjectEntity:115`,
`CartographerTableObjectEntity:81`, `CavelingOasisFountainObjectEntity:19`, plus many buffs via
`getTotalTicks() % n`.

**Rules out / narrows.** arcane-storage itself is *not* exposed to this: its only use of these
counters is a client-side animation frame (`BandDeviceObject:126`). So this does not explain the
standing symptom, but it does mean any consumer scheduling on engine tick counters cannot trust the
harness yet. Worth fixing on its own merits before a second mod depends on it.

---

## 2026-08-30 — The game keeps its own per-boot log history, and it goes back weeks

**Finding.** Independently of the harness's log, the engine writes every boot to
`~/.config/Necesse/logs/<YYYY-MM-DD HHhMMmSSs>.txt` and never prunes it: **2110 files back to
2026-08-09, including 734 from 2026-08-29 alone**. Every line is timestamped, region loads are
logged, every harness command is echoed (`> harness rpc <n> <verb> …`), and so is `World time`. So
the paper trail for every past run has existed all along — yesterday's evidence was never lost, it
was in a directory nobody looked in.

**Use this first** for any "what happened during that run" question, before adding instrumentation
or re-running anything. One boot = one file; a `pytest-all` run = four consecutive files.

---

## 2026-08-30 — Autosave: ruled out for arcane-storage, confirmed firing for arcane-production

**Finding.** Autosave is driven by the wall clock (see the Q3 entry), and it does fire in harness
runs — but not in arcane-storage's. Classifying all 734 of yesterday's boots by mode:

| Mode | Boots | Of which autosaved |
|---|---|---|
| clock ticks (`timescale 20.0` present) | 253 | 1 |
| manual ticks | 481 | 8 |

All 8 manual-mode autosaves are `arcane_production_harness_py`, and each fires **~62s after boot**
(18:16:04 → 18:17:06, and so on) — exactly the 60s threshold, confirming world time tracks real time
1:1 in manual mode. **Zero** `arcane_harness_py` boots autosaved, because arcane-storage's four
processes each live ~12s and never reach 60s.

The one clock-mode instance fired **7s after boot** (`2026-08-29 09h23m27s.txt`, boot 09:23:29, save
09:23:36) because `tickTime` multiplies the real delta by the time modifier, so `settle`'s x20
acceleration crosses a 60s threshold in ~3s. That boot shows 540 `timescale 20.0`/`1.0` pairs.

**Rules out.** Autosave as a cause of the standing arcane-storage symptom. No new test runs were
needed for this — it came entirely from the log archive above.

**Live hazard it *does* identify.** Any consumer whose suite keeps one boot alive past 60s gets a
full autosave mid-run, and because `autoSaves % 15 == 0` holds for the *first* one, it takes the
heavy path: full save, `reloadFileSystem()`, and a world-copy thread running concurrently with the
tests. That is arcane-production today, in all 8 of yesterday's runs. The harness should suppress
autosave under manual ticks the same way it suppresses the unload sweeps.

**Artifact.** `~/.config/Necesse/logs/2026-08-29 09h23m27s.txt` (clock mode, 7s),
`… 18h16m04s.txt` (manual mode, 62s).

---

## 2026-08-30 — Harness log retention fixed

**Finding.** `HarnessServer` now writes one log per boot to
`$XDG_STATE_HOME/necesse-harness/logs/server-<runid>-<seq>.log` (was: a single `server.log` in
`/tmp`, truncated on every boot *and* every restart, one `.previous` kept — so a `pytest-all` run
destroyed 3 of its 4 processes' logs). A `server-latest.log` symlink points at the current one and
`keep_logs` (default 60 ≈ 15 runs) bounds growth. Rotating by name also satisfies what the old
truncation was there for: `_await_ready` needs a file with no stale ready line in it.

**Verified.** Harness self-check 16/16 and `test_persistence` (which restarts) both pass, and the
restart now leaves `…-1.log` and `…-2.log` side by side.

---

## 2026-08-30 — Autosave suppressed under the harness, and a ByteBuddy lesson

**Finding.** Added `Autosave` + `ServerAutoSavePatch` (a `@ModMethodPatch` on `Server.tickAutoSave`, skipped
while suppressed), an `autosave on|off` verb, `autosave` in `query level`, `Harness.set_autosave`, and
suppression by default in the pytest plugin — deliberately *outside* the manual-ticks branch, because clock
mode is worse rather than better: x20 scales the world delta, so the 60s interval arrives in 3s.

A patch was necessary because there is no knob: `Server.autoSaveIntervalInSec` is `public static final int 60`,
so javac inlines it into `tickAutoSave` and changing the field could not change the behaviour.

**Lesson worth keeping.** `Autosave.isSuppressed()` was written package-private and that failed at runtime with
`IllegalAccessError: class necesse.engine.network.server.Server tried to access method
necesseheadlessharness.Autosave.isSuppressed()`. ByteBuddy **inlines an `@Advice` body into the target
method**, so the call is compiled inside `Server` and resolved with `Server`'s access rights: anything an
advice touches must be public. `ManualTicks.claimTick()` is public for exactly this reason. The failure mode is
harsh — the server stops with `SERVER_ERROR` on the first granted tick — but it only appears once a tick runs,
so a suite that never ticks would not catch it.

**Artifact.** `~/.local/state/necesse-harness/logs/server-20260830-123131-153223-1.log`.

---

## 2026-08-30 — `restart()` now re-applies the server-side switches, and that had been hiding things

**Finding.** `Harness` remembers the last requested value of the unload and autosave switches and re-applies
both after a restart. They live in static state inside the JVM, so a fresh process reset them: the suite asked
for suppression **once**, at session start, and from the first restart onward the engine's defaults were
quietly back. Since `pytest-all` is four server processes cut by the three restart tests, **suppression only
ever covered process 1 of 4.** Nothing reported it.

Re-applied from what was last requested rather than from config, so it stays correct whoever set it.

**Two arcane-storage tests were depending on that accident.** `test_a_closed_remote_container_stops_pinning`
needs a live sweep to observe a region being dropped, and it ran in process 4 where the sweep had come back.
Closing the gap broke it — correctly. Its sibling `test_an_open_remote_container_keeps_its_regions_loaded` was
worse off: it *passed*, vacuously, because with no sweep there is nothing for the pin to survive. Both now take
an `automatic_unloading` fixture that turns the sweep on for the test and restores suppression afterwards.

Also fixed a leak in the harness's own `test_the_sweep_can_be_suppressed_and_restored`, which restored the
engine default and left it that way for every later test in the session.

**Verified.** New `test_the_server_side_switches_survive_a_restart` guards it, and was watched failing with the
re-application disabled. Harness self-check 23/23. Full arcane-storage suite: **273 passed, 2 xpassed** green.

---

## 2026-08-30 — Region unloads were rampant, but do not reproduce the standing symptom

**Finding, and a correction.** Earlier notes said process-1 failures ruled out the unload sweep as the cause of
the standing symptom. **That reasoning was wrong**: it assumed the sweep suppression existed when those
failures were recorded, and it was only added the day before. Classifying yesterday's `arcane_harness_py`
boots from the log archive:

| Boots | Saw a real region unload |
|---|---|
| 486 without `autounload off` | **332 (68%)** |
| 205 with it | 2 (1%) |

Both exceptions are explained: those two boots came from an experimental per-test toggle (402 `off`/400 `on`
pairs in one), so the sweep was live between each pair.

**And the spawn region really does unload.** Confirmed directly: with the sweep live, `region_loaded(0,0)` goes
`True -> False` after 700 granted ticks. The synthetic player has no route to the per-tick `keepLoaded()` a real
`ServerClient.tick` performs, so nothing pins the region the player is standing in — which is where the terminal
at spawn+(0,0) lives.

**But it is not sufficient.** Four arms — sweep suppressed or live, crossed with break or no break — all
answered `capacity (1, 40)` correctly afterwards. `query capacity` reloads the region before reading, and the
terminal comes back intact. So unload/reload, even with a deferred removal in flight, does **not** produce the
missing terminal on its own.

**Rules out.** Region unloading as a *sufficient* cause. It stays a real hazard, and a plausible contributor in
combination with the multi-test churn the two xfail tests describe ("needs seven tests' worth of churn to
appear"), but it is not the mechanism on its own evidence.

**Not claimed.** That the flakiness is fixed. One green run against a base rate of roughly 1 failure per 10–15
runs is weak evidence, and both xfail tests are `strict=False` and intermittent, so 2 xpasses is suggestive at
best. What is genuinely new is that the suppression is applied consistently across all four processes for the
first time.

**Artifact.** `docs/debug-data/probe_spawn_region_unload.py`.

---

## 2026-08-30 — Q2 answered in the form that matters: which clocks are in lockstep, measured

**Instrument.** New `query clocks` reads the engine's own counters side by side with the harness budget:
`granted`, `budgetleft`, `manual`, plus `TickManager`'s `totalticks`, `expectedticks`, `skippedticks`,
`frames`, `tickinsecond`, plus `WorldEntity`'s `time`/`worldtime`. All pre-existing engine counters — nothing
new is measured, it was simply invisible from Python, which is why the lockstep gap stayed an argument.

**Finding.** Five repetitions of one identical command sequence, deltas per run:

| Counter | Values | Spread | Verdict |
|---|---|---|---|
| `granted` | 110, 110, 110, 110, 110 | **0** | lockstep |
| `expectedticks` | 0, 0, 0, 0, 0 | 0 | lockstep |
| `skippedticks` | 0, 0, 0, 0, 0 | 0 | lockstep |
| `totalticks` | 2, 2, 1, 1, 1 | 1 | wall clock |
| `frames` | 27, 38, 43, 63, 78 | **51** | wall clock |
| `time`/`worldtime` | 103, 80, 51, 39, 42 ms | **64** | wall clock |

So **game-logic volume is perfectly deterministic** and a test may depend on it. Frame count varies by
roughly 3x for identical work, and the world clock by 2.6x.

**Correction to an earlier entry.** Previous notes framed the world clock as running *ahead* because it
"tracks real time 1:1". The 1:1 part is right and the implication was backwards. Manual ticks make the game
logic run *much faster than real time*: 110 granted ticks is 5.5s of game time performed in ~63ms of real
time, so the world clock ends up about **87x behind the logic** — 0.6 world-ms per granted tick where
lockstep would be 50.0. That direction matters for reasoning about which engine mechanisms misfire: things
gated on world time fire far too *rarely* under manual ticks, which is exactly why arcane-storage's 12s
processes never autosaved while arcane-production's longer ones did.

**What this costs, concretely.** `Server.frameTick` also drives `TileEntityList.frameTick` → `tickMovement`,
so entity movement is integrated a *variable number of times* with variable deltas for identical work.
Anything whose outcome depends on a position, or on the engine's `getTick()`/`getTotalTicks()` counters, is
non-deterministic by construction rather than by bug.

**Guarded.** Two new self-check tests: one asserts identical work costs identical game time, one asserts the
clocks query keeps reporting the distinction. Harness self-check 25/25.

**Artifact.** `docs/debug-data/probe_clocks.py`.

---

## 2026-08-30 — The two defect classes, and why they pull against each other

Worth stating explicitly, because today produced one of each and the fixes point in opposite directions.

* **False failures** — a test fails on wall-clock noise. Caused by leaving an engine mechanism running on
  real time while game logic is frozen. Autosave was one; the unload sweep was another.
* **False passes** — a test cannot fail, because the mechanism it was written to exercise has been
  suppressed. `test_an_open_remote_container_keeps_its_regions_loaded` was in this state today: with the
  sweep off there was nothing for its region pin to survive, so it would have passed with the pin removed
  entirely.

Suppressing a mechanism converts the first into the second. That is strictly better only if the suppression
is *visible* and reversible per test, which is what the `autounload`/`autosave` switches, their `query level`
fields, and arcane-storage's `automatic_unloading` fixture are for. The remaining requirement is that a test
which turns a mechanism back on gets a **reproducible** answer — and per the measurement above, that does not
hold yet for anything driven by frames or the world clock.

---

## 2026-08-30 — Every run tests a different world, which no amount of clock work fixes

**The claim of determinism made earlier today does not hold, and this is the likeliest reason.** Three
consecutive runs of one unchanged tree produced `274 passed, 1 xfailed`, then `3 failed, 271 passed, 1 xfailed`,
then `274 passed, 1 xpassed`. The five identical runs recorded above were weak evidence presented as strong.

**The world is regenerated per run, with a different spawn island every time.** Read straight off the bus tile
in each run's own log:

| Run | bus tile at spawn+(3,1) |
|---|---|
| 154202 | 683,473 |
| 154303 | -1045,9 |
| 154757 | 875,1017 |
| 154849 | 795,1033 |
| 154940 | -309,-1079 |
| 155034 | -693,-839 |

Within a run, boots 1 and 3 agree — the world correctly survives a restart. Across runs nothing agrees. The
harness passes no seed and deletes the world archive on a fresh start, so each run generates a new one.

**Terrain generation is not the random part.** `WorldGenerator.islandSeed(islandX, islandY)` is
`new GameRandom(islandX * 1289969L + islandY * 888161L).nextLong()`, and `WorldSettings` has no seed field at
all -- so a given island position always generates identically. What varies is *which island the spawn lands
on*. That is good news: the randomness has one entry point rather than being diffused through generation.

**Why this outranks the ungated executors as a suspect.** Tests place devices at spawn-relative offsets on
whatever terrain is there, and a different island means different tiles under `spawn+(3,0)`, a different biome,
and a different set of nearby mobs -- which then feed the one scheduler still not gated by the tick budget.
Exact tick counts on a non-identical world is not reproducibility.

**Method note, recorded because it wasted a step.** Differencing only the `FAIL` lines between a failing and a
passing run showed one apparent difference, and it was an artifact: the normalising regex `[0-9]+,[0-9]+` does
not match negative coordinates, so `683,473` and `-1045,9` were treated as different messages. The full
normalised logs differ by hundreds to thousands of lines per boot, which is the honest signal, and the
`busapply` refusal that looked like a lead is deliberate -- `test_the_reason_names_the_other_device` asserts it
via `pytest.raises`.

**Also a gap in the trail: pytest's own output is the one artifact not kept.** The four server boots are saved
per run, but `pytest-all` runs bare, so the names of the three failing tests existed only in a terminal. One
was captured before it scrolled away, `test_multitile_containers::test_a_bus_finds_a_wide_container_from_either_half`;
the other two are unrecoverable. The expensive artifact is retained and the cheap decisive one is discarded.

**Not yet attempted:** pinning the spawn island so a fresh world regenerates identically, which would give both
clean state and stable terrain. The alternative -- keeping one world and resetting state -- risks tests
contaminating each other, which is presumably why the archive is deleted in the first place.

---


**Found by accident, which is the point.** An arcane-production suite was started by hand while an
arcane-storage run was in progress. The arcane-storage run collapsed — `1 failed, 4 passed, 269 errors` — and
the only visible symptom was `java.net.BindException: Address already in use` in the *new* server's log. The
obvious reading is "second server could not start, second run fails". That reading is wrong and it hid a real
defect.

**The mechanism.** Every project used the same default work directory, so every project used the same
`replies.jsonl`, and each fresh boot truncated it. Starting a second suite therefore truncated the first
suite's reply file **while the first run's reader was positioned in it** — the reader's offset was now past
end of file, so replies it was waiting for no longer existed and `ServerDied` was reported against a server
that was perfectly healthy.

**The part worth remembering: the damage does not need the second server to start.** Truncation happens in
Python during launch preparation, before the JVM is spawned and long before it tries to bind. So the second
run failing outright with `BindException` still destroyed the first run. A failed launch was a sufficient
cause, which is exactly why the symptom pointed at the wrong process.

**Fixed by removing the sharing rather than serialising access.** Reply files are now per run
(`replies-<runid>.jsonl`), so nothing needs truncating and a stale reply cannot be read by a run that did not
write it. Work directories are now per world, so projects no longer share a log directory or a pruning
budget.

**A misdiagnosis this also caused, recorded because it was mine.** With both projects writing to one log
directory, "the newest log" was ambiguous. While diagnosing the above I read
`server-20260830-151120-253517-1.log` as belonging to my run; the pid in the name was arcane-production's. Two
projects in one directory makes the most convenient diagnostic habit — look at the newest file — silently
wrong.

**Also corrected here: a wedge that was not one.** The concurrent server was read as deadlocked from
`futex_do_wait` plus a log that had stopped growing. A thread dump said otherwise: the Server Thread was
`TIMED_WAITING` in `TickManager.tickLogic`'s pacing sleep, the light-update executor was parked waiting for
work, and jcmd reported no Java-level deadlock. It was an idle, healthy server waiting for commands while its
driver sat in a real-time wait. `futex_do_wait` on the process is not evidence of a deadlock; the thread dump
is, and it is cheap.

**Still unfixed, and now the known limit on concurrency.** The game server's listen port is fixed, so two
suites still cannot boot at once — the second gets `BindException`. That is now a clean, loud failure rather
than silent cross-run corruption, which is the right shape for an unsupported case, but it is a limitation
worth lifting: the harness drives the server over stdin and never uses that socket, so the port is incidental
to everything the harness does.

---


The gap this closes: manual mode gated `Server.tick` and **nothing else**, so three clocks ran free. Fixed in
the order of how much variance each removed, each verified by re-running `probe_clocks.py`.

**1. World frame ticks — `WorldFrameTickPatch`.** `Server.frameTick` does two unrelated jobs: it pumps the
network, then advances game state via `world.frameTick` and each client's `tickMovement`. Gating the whole
method would freeze the network with it and deadlock the server, since the harness's own command queue drains
from there — so the state half is gated at `World.frameTick`, its own entry point. `ManualTicks` gained a frame
budget beside the tick budget, and the `tick` verb now runs one world frame tick after each granted tick,
matching `ServerGameLoop.update`'s `tick`-then-`frameTick` order. Before this, a burst of N ticks ran with no
frame between any of them: the world clock stood still for the whole burst and movement was never integrated
mid-settle, so N ticks in the harness did not mean N ticks on a server.

**2. Delta size — `TickManagerDeltaPatch` / `TickManagerFullDeltaPatch`.** Fix 1 made the frame count
deterministic and exposed that its *size* was not: delta is `(nanoTime - loopTime) / 1e6 * globalTimeMod`, so a
frame invoked from a tight burst measured microseconds and the world clock effectively stopped — 0.0 world-ms
per granted tick. Both accessors now return the engine's own `msPerTick` (50), **scoped to the calling thread
inside `ManualTicks.runFrame`**. Not pinned globally, and that distinction is load-bearing:
`Server.frameTick` integrates client movement from `getDelta()` on every unpaced loop iteration, outside the
gate, so a global constant would advance client movement a full tick per iteration — faster and no less wrong.

**3. Tick counters — four patches behind `GameClock`.** `TickManager`'s `totalTicks` and `tick` (0..19) are
`nanoTime`-derived and advanced by the loop, so 110 granted ticks moved them by 2 or 3: a rate error of ~40x.
The engine schedules real periodic work off them — mob despawn rolls at `EntityManager:523`, five object
entities, two boss paths, and 21 buff/mob sites on `getTotalTicks() % n`. `getTotalTicks`, `getTick`,
`isFirstGameTickInSecond` and `isGameTickInSecond` are now granted-tick-derived in manual mode. Safe because
nothing in the loop or the save path reads them: pacing works from the private fields, and the only
non-gameplay callers are `Server`'s shutdown log line and the client debug overlay.

**Result, five repetitions of an identical command sequence:**

| Counter | Before | After |
|---|---|---|
| `granted` | 110 ×5 | 110 ×5 |
| `worldframes` | 27, 38, 43, 63, 78 | **110 ×5** |
| `totalticks` | 2, 2, 1, 1, 1 | **110 ×5** |
| `worldtime` | 39–103 ms | **5500 ms ×5** (50.0/tick) |
| `frames` | varies | varies — **correctly** |

Raw loop `frames` still varies and must: that is the unpaced iteration pumping packets and draining commands,
and freezing it deadlocks the server. It no longer advances any game state.

**Guarded.** `test_a_granted_tick_is_worth_exactly_one_tick_of_time` asserts the exchange rate directly — one
world frame tick, one engine tick, and 50ms of world time per granted tick.

---

## 2026-08-30 — The suite is now reproducible, and one flake turned out to be two different things

**Finding.** Five consecutive full arcane-storage runs produced byte-identical outcomes, including *which*
xfail-marked test failed. The two "entity churn" tests had been described as intermittent at roughly one run
in three; they are no longer intermittent at all, and they split cleanly:

* `test_scheduler::test_a_bus_with_nowhere_to_put_things_says_so_within_a_second` — **now passes on every run.**
  It was a clock artifact: the state it asserts is re-derived on a one-second heartbeat, and a "second" was
  wall-clock, so whether the heartbeat landed before the query depended on machine speed. Its xfail marker is
  removed and it is a real test again. Its stated diagnosis — "fix belongs in the harness's object lifecycle" —
  was wrong, and is worth recording as wrong.
* `test_bus_names::test_the_terminal_reports_names_not_only_coordinates` — **fails on every run.** A genuine
  arcane-storage defect, not a harness one: bus ordinals derive from device-list join order, so a tile that has
  had more than one `BusObjectEntity` registered over a run can be numbered from an instance no reader sees.
  The fix is a deterministic `tileX`/`tileY` enumeration, which changes player-visible bus numbering and is
  therefore a design decision rather than more diagnosis.

**Why this matters beyond the two tests.** A stale xfail reason is the "false pass" failure mode in its purest
form: both markers claimed intermittency, so a permanently-passing test and a permanently-failing one were
being treated identically and neither was being looked at.

**Still not claimed.** That every source of non-determinism is gone. Two remain unexamined, both structural:
the light-update executor and `Level.executor()`'s `FutureAITask` mob AI complete on their own threads and are
not gated by anything. Nothing has yet been observed to depend on them, and no test currently exercises mobs
heavily — which is exactly why that is an absence of evidence rather than evidence of absence.
