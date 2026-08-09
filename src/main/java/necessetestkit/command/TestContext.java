package necessetestkit.command;

import java.awt.Point;
import java.util.ArrayList;

import necesse.engine.commands.CommandLog;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.level.maps.Level;

/**
 * Everything a verb needs, and nothing it has to find for itself.
 *
 * <p>All of this is already resolved by the time a verb runs: the work is on the server thread,
 * the regions the verb's coordinates address are loaded, and {@link #client} is a real player
 * whether or not a person is connected.
 */
public final class TestContext {

   /** The level the verb operates on -- the world's start level. */
   public final Level level;

   /**
    * The tile every coordinate is relative to. Scenarios address tiles as offsets from spawn so
    * that they do not depend on world generation, which differs per seed.
    */
   public final Point spawn;

   public final Server server;

   /**
    * The acting player. Never null when a verb has declared it needs one; may be a headless
    * client rather than a person, and a verb should not care which.
    */
   public final ServerClient client;

   /** The verb's arguments, with the verb itself at index 0. */
   public final ArrayList<String> args;

   public final CommandLog logs;

   public TestContext(Level level, Point spawn, Server server, ServerClient client,
                      ArrayList<String> args, CommandLog logs) {
      this.level = level;
      this.spawn = spawn;
      this.server = server;
      this.client = client;
      this.args = args;
      this.logs = logs;
   }

   /** An argument as an int, or throws so the command layer reports a missing/!numeric argument. */
   public int intArg(int index) {
      return Integer.parseInt(this.args.get(index));
   }

   public String arg(int index) {
      return this.args.get(index);
   }

   public int argCount() {
      return this.args.size();
   }

   /** Absolute tile X for a spawn-relative offset. */
   public int tileX(int dx) {
      return this.spawn.x + dx;
   }

   public int tileY(int dy) {
      return this.spawn.y + dy;
   }

   /**
    * Records an assertion. Passing prints what held, failing prints what was expected against
    * what was found -- a failure that only says "failed" costs a debugging round trip.
    */
   public boolean check(boolean ok, String what, String detail) {
      if (ok) {
         this.logs.add("PASS " + what);
         return true;
      }

      this.logs.add("FAIL " + what + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
      return false;
   }

   public boolean fail(String message) {
      this.logs.add("FAIL " + message);
      return false;
   }

   public void info(String message) {
      this.logs.add(message);
   }
}
