package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;

/**
 * The game clock as granted ticks define it, for the patches that replace {@link TickManager}'s wall-clock one.
 *
 * <p><b>The problem.</b> {@code TickManager.tickLogic} derives {@code totalTicks} and {@code tick} (0..19,
 * reset every real second) from {@code System.nanoTime()}, and advances them from the *loop* rather than from
 * {@code Server.tick()}. Manual mode gates {@code Server.tick} and cannot gate the loop, so the two diverge
 * completely: measured over an identical command sequence, 110 granted ticks advanced the engine's counter by
 * 2 or 3 -- a rate error of roughly 40x, plus a run-to-run spread.
 *
 * <p><b>What that breaks.</b> The engine schedules real periodic work off these counters, and all of it
 * misfires. Once-per-second work -- mob despawn rolls at {@code EntityManager:523}, {@code HomestoneObjectEntity},
 * {@code MusicPlayerObjectEntity}, {@code CartographerTableObjectEntity}, {@code CavelingOasisFountainObjectEntity},
 * {@code FallenWizardRespawnEvent}, two boss tick paths -- and every buff or mob keyed on
 * {@code getTotalTicks() % n}, of which there are 21. Under manual ticks that work fires at a rate decided by
 * how fast the suite happened to run, which is non-determinism in one direction and untested behaviour in the
 * other: a test that grants thousands of ticks was, until now, exercising almost none of it.
 *
 * <p><b>Why replacing the accessors is safe.</b> Nothing in the loop or the save path reads them. The engine's
 * own pacing works from the private fields directly, and the only non-gameplay call sites are the
 * "Total ticks:" line in {@code Server}'s shutdown log and the client's debug overlay. So the counters can be
 * redefined for gameplay without touching how the server decides when to tick.
 *
 * <p>Active only in manual mode. In clock mode the engine's values are already correct and are returned
 * untouched.
 *
 * <p>Every method here is public because ByteBuddy inlines advice bodies into the patched class, so anything an
 * advice calls is resolved with *that* class's access rights -- package-private fails at runtime with
 * {@code IllegalAccessError}. Learned from {@link Autosave}.
 */
public final class GameClock {

   private GameClock() {
   }

   /** Whether the harness is defining the game clock, rather than the wall clock. */
   public static boolean overriding() {
      return ManualTicks.isManual();
   }

   /** Total granted ticks, standing in for the engine's wall-clock {@code totalTicks}. */
   public static long totalTicks() {
      return Ticks.count();
   }

   /**
    * Position within the current game second, 0..19.
    *
    * <p>Engine code compares this against a literal -- {@code getTick() == 1} is its idiom for "once a
    * second" -- so the modulus has to be the engine's own {@code ticksPerSec} for those comparisons to keep
    * meaning what they say.
    */
   public static int tickInSecond() {
      return (int)Math.floorMod(Ticks.count(), (long)TickManager.ticksPerSec);
   }

   /**
    * The predicate form, for {@code isFirstGameTickInSecond} and {@code isGameTickInSecond}.
    *
    * <p>The engine's version also requires its {@code gameTick} flag, which under manual mode reflects
    * whether the *loop* decided to tick and is therefore false for almost every granted tick. Dropping that
    * condition is the point: in manual mode a granted tick is the game tick.
    */
   public static boolean isTickInSecond(int tick) {
      return tickInSecond() == (int)Math.floorMod((long)tick, (long)TickManager.ticksPerSec);
   }
}
