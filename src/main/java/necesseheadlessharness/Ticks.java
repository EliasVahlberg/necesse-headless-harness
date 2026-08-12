package necesseheadlessharness;

import necesse.level.maps.Level;

/**
 * Counts server ticks, so a test can let time pass and say how much passed.
 *
 * <p>Without this the harness can only make things happen instantly, one command at a time, which is enough
 * to test what a piece of code computes and nothing about what a system does over time. Anything with a
 * timer, a cooldown, a queue or a cascade is invisible to it: a consumer mod ends up calling the work
 * directly, which tests the arithmetic and steps over the scheduling. The first real bug of that kind found
 * in a consumer was two devices moving the same items back and forth forever, which no single-shot test could
 * express.
 *
 * <p>Counted per level rather than globally. {@code Level.serverTick} runs once per loaded level per server
 * tick, so counting invocations would run at a multiple of the real rate as soon as a second level loads --
 * a cave, an incursion, a settler expedition -- and a test that waited "sixty ticks" would silently wait for
 * twenty. The number reported is for the level the harness's player is on, which is the level a test is
 * talking about.
 *
 * <p><b>Waiting is the caller's job, and deliberately so.</b> A {@code settle} verb that slept until the
 * count advanced would deadlock: verbs run on the server thread, so a verb that waits for a tick is waiting
 * for itself. The counter is exposed instead, and the client polls it -- which is why the Python helper, not
 * the Java side, owns the loop.
 */
public final class Ticks {
   private static volatile long count;
   private static volatile int levelHash;
   private static volatile boolean levelKnown;

   private Ticks() {
   }

   /** Called from the tick patch, on the server thread. */
   public static void onLevelTick(Level level) {
      if (level == null) {
         return;
      }

      if (!levelKnown || level.getIdentifierHashCode() == levelHash) {
         count++;
      }
   }

   /**
    * Binds counting to one level, called when the harness's player arrives on it.
    *
    * <p>Before this is known every level's tick is counted, which is wrong but harmless: nothing can have
    * asked to wait yet.
    */
   public static void watch(Level level) {
      if (level != null) {
         levelHash = level.getIdentifierHashCode();
         levelKnown = true;
      }
   }

   public static long count() {
      return count;
   }
}
