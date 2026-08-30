package necesseheadlessharness;

import necesse.engine.network.server.Server;

/**
 * Keeps the engine's periodic autosave out of the middle of a test.
 *
 * <p><b>Why this exists.</b> {@code Server.tickAutoSave} fires when {@code saveTime <= worldEntity.getTime()},
 * and world time is <b>not</b> under {@link ManualTicks}' control. Manual ticks gate {@code Server.tick}, but
 * {@code Server.frameTick} runs in full on every loop iteration, and that is the path that reaches
 * {@code WorldEntity.tickTime} -- so the world clock keeps advancing at wall-clock rate while the world's logic
 * is frozen. The threshold is therefore crossed by real time passing, and the save then executes on whichever
 * granted tick happens to come next, in the middle of whatever a test was doing.
 *
 * <p><b>This is measured, not theorised.</b> The engine keeps a log per boot in
 * {@code ~/.config/Necesse/logs/}, and classifying 734 boots from one day of harness runs found autosave firing
 * in 8 of them, every time roughly 62 seconds after boot -- which is the engine's 60-second interval, and is
 * incidentally a clean confirmation that world time tracks real time 1:1 under manual ticks. The suites that
 * escaped it did so only because they restart often enough that no single process lives a full minute.
 *
 * <p><b>What firing costs.</b> Not merely a save. {@code shouldBackup} is {@code autoSaves % 15 == 0}, which is
 * true for the <b>first</b> one, so the first autosave of every process takes the heavy path: a full save, then
 * {@code reloadFileSystem()}, then a freshly spawned thread copying the entire world directory while the test
 * carries on running. A suite that grows past a minute per process acquires all of that, once, at a time
 * determined by the wall clock -- which is the definition of an unreproducible failure.
 *
 * <p><b>Why a patch rather than a setting.</b> There is no knob. {@code Server.autoSaveIntervalInSec} is
 * {@code public static final int 60}, so javac inlines it into {@code tickAutoSave} and changing the field --
 * even reflectively -- would not change the behaviour. {@link ServerAutoSavePatch} skips the method instead.
 * Contrast {@link Unloading}, which does have a setting to raise and so needs no patch.
 *
 * <p><b>Nothing depends on autosave.</b> Persistence across a restart comes from the shutdown save: the Python
 * client's {@code restart} returns ticks to automatic and then stops the server, and a clean stop saves. Tests
 * that assert on state surviving a restart are asserting about that save, not about this one.
 */
public final class Autosave {

   /**
    * Suppressed rather than "off", because the engine's own behaviour is the default and stays the default.
    * A client process, where the harness is dormant, never sets this.
    */
   private static boolean suppressed;

   private Autosave() {
   }

   /** Whether the engine's periodic autosave is running, which is the engine's default. */
   public static boolean isAutomatic() {
      return !suppressed;
   }

   /**
    * Read by {@link ServerAutoSavePatch} on the server thread, once per granted tick.
    *
    * <p><b>Public because it has to be, not as a convenience.</b> ByteBuddy inlines an {@code @Advice} body
    * into the target method, so the call ends up compiled inside {@code necesse.engine.network.server.Server}
    * and is resolved with that class's access rights. Package-private was the first attempt and it failed at
    * runtime, on the first granted tick, with {@code IllegalAccessError: class Server tried to access method
    * necesseheadlessharness.Autosave.isSuppressed()} -- which stops the server with {@code SERVER_ERROR} rather
    * than degrading, so it is loud but it is only loud once a tick actually runs. {@link ManualTicks#claimTick()}
    * is public for the same reason.
    */
   public static boolean isSuppressed() {
      return suppressed;
   }

   /**
    * Turns the periodic autosave off, or back on.
    *
    * <p>Turning it back on <b>restarts the interval</b>, and this is a deliberate divergence from
    * {@link Unloading#setAutomatic}, which resumes from wherever its buffers had counted to. The difference is
    * that an unload buffer is a counter while {@code saveTime} is an absolute timestamp: after any meaningful
    * suppression it is already in the past, so resuming would not resume, it would save immediately -- landing
    * the thing this class exists to prevent on the first tick after a test asked for normal behaviour back.
    *
    * <p>{@code server} may be null, which costs only the timer restart. That keeps this callable from anywhere
    * that has the flag but not the server, rather than making the caller invent one.
    */
   public static void setAutomatic(Server server, boolean automatic) {
      if (automatic == !suppressed) {
         return;
      }

      suppressed = !automatic;
      if (automatic && server != null) {
         server.startSaveTimer();
      }
   }
}
