# Necesse Headless Harness

![Necesse Headless Harness](art/banner/banner.png)

A headless integration test harness for Necesse mods. It drives a real dedicated server from
plain-text scenario files — placing objects, filling containers, opening UIs, clicking slots,
asserting what happened — with **no rendering and nobody playing the game**.

> **Alpha, and unannounced on purpose.** The API will change. It is being built against one real
> mod first, because the only way to find out whether an API is right is to use it for something
> that matters. If you found this before it was announced, it works, but do not expect stability.

## Letting time pass

`harness query tick` reports the server tick count for the level under test. The Python client's
`settle(ticks)` gets that much game time to pass.

This is the difference between testing what a piece of code computes and testing what a system does. Without
it a consumer mod can only invoke its own work directly, one command at a time, which verifies arithmetic and
steps over scheduling: timers, cooldowns, queues and cascades are all invisible. The first bug it found in a
consumer was two devices moving the same items back and forth forever while every single-shot test passed --
and, worse, while the observable totals looked settled.

Counted per level, not globally: `Level.serverTick` runs once per loaded level per server tick, so a global
count runs at a multiple of the real rate the moment a second level loads and "wait sixty ticks" silently
waits for twenty. The level watched is the one the harness's player is on.

### Game time is detached from the wall clock by default

Ticks are **granted**, not waited for. `ticks manual` stops the server's game tick, and `tick N` runs exactly
N of them on demand. On the first consumer's 163 tests this took the suite from **333 seconds to 20**.

Two measurements say why it was worth doing, and they are the same fact seen twice:

| | Before | After |
|---|---|---|
| One game tick | 50ms (the server's fixed 20/second) | ~0.2ms, granted |
| One harness command | **49.89ms** -- exactly one tick | ~1.3ms |
| Suite of 163 tests | 333s | 20s |

A command cost a whole tick because every verb is marshalled onto the server thread and the caller waits for
the next tick to pick it up. So 186 of those 333 seconds were `settle` polling, and most of the rest was
command latency. Nothing was slow; everything was waiting.

**Determinism matters more than the speed.** Nothing ticks between a test's commands, so a fixture placing
seven objects is atomic in game time and the systems under test cannot act on a half-built world. A test that
asks for sixty ticks gets sixty, deliberately, rather than however many fitted into three seconds.

`ticks auto` returns to the clock. Per test, the pytest marker `realtime` does the same, for the one case that
genuinely needs it: a test asserting about *real* elapsed time cannot be stepped past, because granting ticks
does not advance the wall clock. `--clock-ticks` runs a whole suite on the clock, which is the control for
deciding whether detached ticks are involved in a failure.

### How it works, and two ways it can be got wrong

The engine makes this possible by separating the two rates itself. `ServerGameLoop.update` calls
`server.tick()` only when `isGameTick()` is set, and calls `server.frameTick()` on **every** iteration -- and
`Server.frameTick` is where `packetManager.tickNetworkManager()` and the packet drain live. Networking
therefore survives frozen game time, so commands arrive, execute and are answered while nothing in the world
moves. Had packet processing been inside `tick()`, this approach would deadlock instead of work.

So: `ServerTickPatch` gates `Server.tick()` on a granted budget, and `ServerFrameTickPatch` drains queued
verbs from the frame instead of the level tick. **That move is what makes the freeze usable rather than a
hang** -- the queue used to be drained inside `Server.tick()`, which is the very thing being skipped, so the
verb that would grant the next tick would have been waiting for a tick to be run.

Two things were tried first and are recorded because neither is obviously wrong:

- **Speeding the clock up instead of stopping it.** `TickManager.globalTimeMod` is the game's own
  fast-forward, and at x20 the suite ran in 48 seconds -- and went flaky, three or four failures a run, never
  the same ones, each passing in isolation. The speed corrupted nothing. Setup had been *accidentally* atomic
  because a fixture's commands each took a tick, and acceleration removed the accident. `timescale` remains as
  a verb for use with `--clock-ticks`.
- **Driving the loop with `globalTimeMod` rather than `maxFPS`.** These look equivalent and are not: the
  modifier also scales `TickManager.getDelta()`, which `frameTick` passes to `tickMovement` every iteration,
  so a large value makes the synthetic player move a hundred times too fast. Manual mode raises `maxFPS`
  instead, leaving deltas honest; the frame rate matters only because the frame is where commands are drained,
  making it the ceiling on command latency.

### What detaching time will expose in a consumer

Removing the free settling between commands surfaces anything that was relying on it. Expect this, because it
is information rather than breakage: in the first consumer it found a real bug -- a bus numbering itself
against a peer that had been removed but not yet swept out of `entityManager`, reachable in play by breaking
and rebuilding within one tick -- and two test fixtures that cleared state and then ticked, letting
not-yet-removed entities rebuild what had just been cleared.

The general shape is engine work deferred to a tick: entity removal above all. A fixture wanting a clean world
should settle, clear, then settle again.

## Why this exists

A mod's pure logic is easy to unit test and rarely where the bugs are. The bugs are in how it
behaves *inside a running game*: objects on a real level, containers opened by a real player,
state surviving a real save and reload. That is normally checked by hand, which does not scale and
does not get repeated — so it stops happening, and regressions land.

This makes those checks scriptable and repeatable, which also happens to suit development with an
AI coding agent: an agent can run the suite and read the failures itself, instead of asking you to
go and look.

## What a scenario looks like

```
harness clear 8
harness place storagebox 3 0
harness fill 3 0 ironbar 25
harness expect item 3 0 ironbar 25

harness player spawn
harness give ironbar 10
harness open 3 0
harness quickstack
harness expect held ironbar 0
harness expect item 3 0 ironbar 35
harness expect total ironbar 35
harness player despawn
```

Every line is a server console command, so any prefix of a scenario can be pasted into a live
server to debug a failure by hand. That is deliberate: the format is data, not code.

Note what that example does *not* mention: this mod. It places a vanilla chest and quick-stacks
into it. **The harness can drive a mod whose source you do not have**, because the verbs address
everything by string ID.

## The player is not a person

`player spawn` connects a client with no socket. `NetworkInfo` is a four-method abstract class and
the game already ships `InvalidNetworkInfo`, whose `send` discards its bytes, so a socketless
client is a state the engine supports rather than a hole punched in it — `OneWorldMigration`
builds one during save migration, and singleplayer's local client legitimately has one too.

This matters because half of what mods add is containers, and a container is built from a player's
inventory. Without it, every container test needs a human.

## Install

The harness is a **normal installed mod**, not something you copy into your source, and not a dev mod.

```bash
make install        # builds and copies the jar into ~/.config/Necesse/mods/
```

It has to be installed rather than dev-loaded because the game accepts exactly **one** dev mod:
`DevModProvider.devMod` is a single String, and `LoadedDevMod.validateDevFolderAndReturnJar`
rejects a dev folder holding more than one file. Your mod keeps that slot. `DesktopPlatform`
registers `ModsFolderModProvider` on dedicated servers as well as clients, so both load together.

Being a separate mod also means the `Level.serverTick` patch that makes any of this safe lives in
one place. It binds to an exact method signature and will break on a game update — once, here,
rather than in every mod that copied it. And nothing test-related ends up in your shipped jar.

## Run

```bash
# prove the harness works in your install, before blaming your own mod
tools/run_scenario.sh tests/scenarios/selfcheck.txt

# your mod
MOD_UNDER_TEST=/path/to/yourmod/build/jar \
   tools/run_scenario.sh /path/to/yourmod/tests/scenarios/*.txt
```

Environment: `MOD_UNDER_TEST` (dev mod's jar directory; empty runs the harness alone),
`SCENARIO_DIR` (where `run <name>` looks; defaults to the first scenario's directory),
`HARNESS_WORLD` (must contain `harness`, since a fresh run deletes it), `HARNESS_DEADLINE`,
`NECESSE_GAME_DIR`.

**A run cannot hang.** A deadlock does not stop Necesse: `ThreadFreezeMonitor` writes a crash log
and leaves the JVM alive with its command thread no longer reading stdin. The runner therefore
bounds itself, checks the server is alive between commands, bounds the stop, and prints any crash
log the game wrote during the run. Before that was true, a deadlock looked like a test run that
took as long as whatever timeout happened to wrap it.

## Verbs

Generic, because they can be said without knowing your mod:

| Verb | Meaning |
|---|---|
| `place <object> <dx> <dy>` | by object string ID, or an alias you registered |
| `break <dx> <dy>` | remove it |
| `fill <dx> <dy> <item> <n>` | into whatever holds an inventory there |
| `clear <radius> [tile]` | flatten an area around spawn |
| `give <item> <n>` | into the player's inventory |
| `open <dx> <dy>` | `GameObject.interact`, the same door a player uses |
| `close` | close the open container |
| `click <slot> <ACTION>` | a raw `ContainerAction` |
| `quickstack` / `restock` | the engine's own slot conventions, so they work on vanilla chests |
| `expect item\|total\|held` | assertions |
| `player spawn\|despawn` | the socketless client |
| `ticks manual [fps]\|auto` | detach game time from the clock, or reattach it; reports the mode with no argument |
| `tick [n]` | run n game ticks now. Manual mode only, so that a test cannot get its ticks *plus* the clock's |
| `timescale [x]` | the game's own fast-forward, for use with `--clock-ticks`. Ignored under manual ticks |
| `query tick` | how much game time has passed on the watched level |
| `run <name>` / `echo <text>` | compose and annotate |

Coordinates are **relative to the world spawn tile**, so scenarios do not depend on the seed.

## Adding your own verbs

```java
public void postInit() {
   Harness.registerObjectAlias("unit", "mymodstorageunit");
   Harness.registerExpectation(new MyCapacityExpectation());   // expect capacity <dx> <dy> <n>
   Harness.registerVerb(new MyBenchmarkVerb());                // a whole new verb
}
```

Registering over something the harness ships is allowed and intended. The built-in `expect item`
counts one tile's inventory, which is right for a chest and wrong for anything that aggregates
across containers — so a mod must be able to redefine the word rather than invent a second one
meaning the same thing.

**Your harness-facing classes must not be in your released jar.** This is the one thing to get
right, and a `try/catch` will not save you.

`LoadedMod.loadClasses` defines **every** class in a mod jar as the mod loads, and turns a
`LinkageError` or `ClassNotFoundException` into a fatal `ModLoadException`. So a single class
referencing a harness type is enough to make your mod refuse to load for anyone who has not
installed the harness -- and it fails during mod loading, before any of your code runs, so there is
no call site left to guard. The symptom misleads too: the loader then dies with a
`NullPointerException` about a null dispose method.

The fix is that test code should not ship anyway. Build two jars:

```groovy
tasks.named('buildModJar') { exclude "yourmod/harness/**" }   // released
tasks.register('buildTestModJar', Jar) { ... }                // build/testjar, includes them
```

and point `MOD_UNDER_TEST` at the test one. `optionalDependencies` in `mod.info` is still worth
declaring, so load order is right when the harness *is* present, and a `try/catch (Throwable)`
around your own registration call is still worth having -- with the classes excluded that is the
path actually taken, and `NoClassDefFoundError` is an `Error` which `catch (Exception)` misses.

Verified both ways, by booting a dedicated server with the harness removed from the mods folder:
shipping the classes is fatal, excluding them loads cleanly and logs one line saying the verbs were
not registered.

## Driving it from Python, with pytest

The scenario format is a one-way pipe: a line goes in, and output has to be recognised in a game
log. That is enough for regression scenarios and not enough for a test framework, which needs to
know *which* reply belongs to *which* command, and needs values rather than verdicts. So the harness
also speaks a correlated request/reply protocol:

```
harness rpc 7 query capacity 4 0
```

writes one JSON line to the file named by `-Dnecesseheadlessharness.rpc`:

```json
{"id":"7","ok":true,"verb":"query","lines":[],"checks":[],"data":{"used":1,"total":80}}
```

`rpc` is a decorator around the normal command path, so every verb -- built-in or registered by a
consumer -- works through it without knowing. `hello` reports the protocol version and the whole
vocabulary. `query` returns the value that `expect` would have compared, computed by the same code,
so the two cannot drift.

```bash
make venv     # .venv with the client installed editable
make pytest
```

```python
def test_a_chest_holds_what_you_put_in_it(harness):
    harness.place("storagebox", 2, 0)
    harness.fill(2, 0, "ironbar", 40)
    assert harness.item_at(2, 0, "ironbar") == 40
```

The `harness` fixture gives each test a cleared area and a fresh player; `harness_server` boots one
JVM for the session, because that is the only expensive part. A consumer overrides `harness_config`
to point at its own jar and adds its own fixtures -- the same split as in Java, where the consumer
registers its own verbs.

Scenario files are not going away. They are the artifact you can paste into a live server to watch a
failure happen, and `Harness.as_scenario()` prints the lines a Python test sent for exactly that
reason.

## There is no CI, and there cannot be

Building needs `Necesse.jar` from a licensed install; running needs `Server.jar` and a real world.
No hosted runner can do either. So there is no badge, and there will not be one — verification is
local by nature, and the convention here is to say what you actually ran.

## Licence

MIT. See `LICENSE`. Necesse itself is proprietary and none of it is redistributed here.
