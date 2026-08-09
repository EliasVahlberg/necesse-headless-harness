# Necesse Headless Harness

A headless integration test harness for Necesse mods. It drives a real dedicated server from
plain-text scenario files — placing objects, filling containers, opening UIs, clicking slots,
asserting what happened — with **no rendering and nobody playing the game**.

> **Alpha, and unannounced on purpose.** The API will change. It is being built against one real
> mod first, because the only way to find out whether an API is right is to use it for something
> that matters. If you found this before it was announced, it works, but do not expect stability.

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

**The soft-dependency trap.** Declare the harness under `optionalDependencies`, then keep every
reference to kit types inside one class that nothing else touches, and call it from a
`try/catch (Throwable)`. With the harness absent, merely *loading* a class that mentions a missing
type throws `NoClassDefFoundError`, which is an `Error` and not caught by `catch (Exception)`.
Get this wrong and players without the harness cannot load your mod at all.

## There is no CI, and there cannot be

Building needs `Necesse.jar` from a licensed install; running needs `Server.jar` and a real world.
No hosted runner can do either. So there is no badge, and there will not be one — verification is
local by nature, and the convention here is to say what you actually ran.

## Licence

MIT. See `LICENSE`. Necesse itself is proprietary and none of it is redistributed here.
