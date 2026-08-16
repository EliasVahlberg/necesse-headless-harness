package necesseheadlessharness;

import necesse.engine.GameLog;
import necesse.engine.Settings;
import necesse.engine.commands.CommandsManager;
import necesse.engine.modLoader.annotations.ModEntry;
import necesseheadlessharness.command.HarnessCommand;

/**
 * A headless integration test harness for Necesse mods.
 *
 * <p>The harness exists because the interesting bugs in a mod are not in its pure logic -- those a unit
 * test catches -- but in how it behaves inside a running game: objects placed on a real level,
 * containers opened by a real player, state surviving a real save and reload. Checking that by
 * hand does not scale and does not get repeated.
 *
 * <p>It installs as a normal mod rather than being copied into yours, for three reasons. The
 * {@code Level.serverTick} patch that makes any of this safe binds to an exact method signature
 * and will break on a game update -- once, here, rather than in every mod that copied it. Nothing
 * test-related ends up in your shipped jar. And because the verbs address everything by string ID,
 * the harness can drive a mod whose source you do not have.
 *
 * <p>Registration is deliberately thin: this mod adds one chat command and one bytecode patch, and
 * registers no items, objects, tiles or packets, so it cannot desync a client from a server.
 * {@code clientside} is nonetheless false, because the server needs it -- the harness talks to a
 * dedicated server's console.
 */
@ModEntry
public class NecesseHeadlessHarness {

   public static final String MOD_ID = "elias.necesseheadlessharness";

   public void init() {
   }

   /**
    * The command is registered in {@code postInit} rather than {@code init} so that every mod's
    * own registration has already happened. A consumer mod registering verbs in its own
    * {@code postInit} therefore cannot lose a race: verbs live in a plain map here, not in one of
    * the game's registries, and the game's registries are closed by this point anyway.
    */
   public void postInit() {
      if (!Harness.isActive()) {
         // Dormant in a client, including its singleplayer server. This mod exists to drive a
         // dedicated server from another process; in a client it has nothing to do and no business
         // being in the way.
         //
         // Not a theoretical tidiness argument. Installed alongside a mod under development, this
         // was loading into interactive play sessions, and Elias hit a hang where no key did
         // anything once he started hosting a world -- gone when he disabled this mod. The
         // mechanism is still unidentified; being inert removes the question rather than answering
         // it, which is the right trade for a test tool.
         GameLog.out.println("Headless harness: dormant in this client; launch with -harness to enable");
         return;
      }

      CommandsManager.registerServerCommand(new HarnessCommand());
      ModBridges.loadAll();
      keepTheSyntheticClientAlive();
   }

   /**
    * Stops the server dropping the harness's player, and stops it pausing if it ever does.
    *
    * <p>Two server settings, both wrong for a synthetic client and both only reachable from inside the
    * game process, which is why they are set here rather than in a config file.
    *
    * <p><b>The kick.</b> {@code ServerClient.tickTimeConnected} measures how long since a packet arrived
    * <i>from</i> a client. The harness's player never sends one -- it is constructed in-process, and a
    * session's counters read {@code Received: 0 B (0 packets)} -- so that interval grows without bound. Once
    * it passes {@code maxClientLatencySeconds}, a counter climbs for 100 ticks and the client is disconnected
    * as not responding. Zero disables the check.
    *
    * <p><b>Why that is fatal rather than untidy.</b> With no clients the server pauses, and a paused server
    * does not call {@code world.serverTick()}, so no level ticks, so the {@code Level.serverTick} patch that
    * drains {@link ServerThreadTasks} never runs -- and every verb after that point fails with "the server
    * thread did not run X", permanently. The symptom is a whole suite collapsing partway through with the
    * first failure long after the cause.
    *
    * <p>It went unnoticed until tests could let time pass: a suite that drove transfers by hand finished
    * inside the timeout, while one that waits for scheduled work spends its time not sending packets. Both
    * settings are safe for a test server and are not written to any config.
    */
   private static void keepTheSyntheticClientAlive() {
      Settings.maxClientLatencySeconds = 0;
      Settings.pauseWhenEmpty = false;
      GameLog.out.println("Headless harness: client latency kick and empty-server pause disabled");
   }
}
