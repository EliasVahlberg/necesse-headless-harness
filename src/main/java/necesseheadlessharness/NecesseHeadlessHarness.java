package necesseheadlessharness;

import necesse.engine.GameLog;
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
   }
}
