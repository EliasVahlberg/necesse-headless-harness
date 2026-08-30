package necesseheadlessharness;

import java.util.concurrent.atomic.AtomicLong;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.network.server.Server;

/**
 * Detaches game time from the wall clock, so a test grants ticks instead of waiting for them.
 *
 * <p><b>Why this exists.</b> A headless suite spends almost all of its time waiting, and measurement said
 * so precisely. The first consumer's 163 tests took 333 seconds, of which 186 were a client polling for
 * ticks to elapse at the server's fixed 20 a second, and most of the remainder was command latency:
 * 49.89ms per command, which is 20.0 commands a second against a 20-tick server -- exactly one tick each,
 * because every verb is marshalled onto the server thread and the caller waits for the next tick to pick
 * it up. Both numbers are the same fact seen twice. Nothing was slow; everything was waiting.
 *
 * <p><b>Why the obvious fix is not enough, which is worth recording because it was tried first.</b> The
 * engine has a speed control -- {@link TickManager#globalTimeMod}, driven from a debug key in the game's
 * own menus -- and turning it up does work: the same suite ran in 48 seconds at x20. But it also went
 * flaky, three or four failures a run, never the same ones, every one passing in isolation. The cause was
 * not the speed. At normal speed a fixture's commands each take about one tick, so a seven-object setup
 * spans seven ticks and the devices under test barely act during it; run the server twenty times faster
 * and the same setup spans a few hundred ticks, so buses start moving items around a half-built network.
 * The tests were correct and their initial conditions were not. Acceleration had been relying on an
 * accident, and removing the accident exposed every test that depended on it.
 *
 * <p><b>So the fix is to stop time rather than to speed it up.</b> In manual mode the server's game tick
 * is skipped unless a test has granted one, while the frame loop keeps running. That gives three things at
 * once: setup is atomic in game time because nothing ticks between commands; a test that needs sixty ticks
 * gets exactly sixty, on purpose, rather than however many fit in three seconds; and both are faster than
 * before, because neither waits on a clock.
 *
 * <p><b>The engine separates the two rates for us, which is what makes this possible at all.</b>
 * {@code ServerGameLoop.update} calls {@code server.tick()} only when {@code isGameTick()} is set, and
 * calls {@code server.frameTick()} on every iteration -- and {@code Server.frameTick} is where
 * {@code packetManager.tickNetworkManager()} and the packet drain live. Networking therefore survives
 * frozen game time, so commands keep arriving and replies keep leaving while nothing in the world moves.
 * If packet processing had been inside {@code tick()} this approach would deadlock instead.
 *
 * <p><b>One setting is changed on entering manual mode: the frame rate.</b> The frame is where queued verbs
 * are drained, so the frame rate is the ceiling on command latency -- at the server's usual twenty a second
 * that ceiling is 50ms, which is exactly what a command was measured to cost. Raising it to a thousand takes
 * command latency to about a millisecond, so the second of the two measured costs disappears too.
 *
 * <p>Granted ticks do not come from the loop at all. The {@code tick} verb calls {@code Server.tick()}
 * directly, as many times as asked, on the server thread it is already running on. An earlier version handed
 * the loop a budget and let it spend it, which forced the client to poll for completion -- and since each
 * poll is itself a command served on that same thread, the polling competed with the loop it was waiting
 * for: 6.4ms per tick, nearly all of it the client's own round trips.
 *
 * <p><b>What not to do, recorded because it was done and cost real time to diagnose.</b> Driving the loop
 * with {@code globalTimeMod} instead of {@code maxFPS} looks equivalent and is not. That modifier also scales
 * {@code TickManager.getDelta()}, which {@code Server.frameTick} passes to {@code tickMovement} on every
 * iteration; at ten thousand, every frame integrates movement with the delta pinned to its 100ms ceiling, a
 * thousand times a second, and the synthetic player moves a hundred times too fast. The suite then failed
 * three tests a run, in different places each time, and it read as flakiness in the tests rather than as a
 * fault in the harness.
 */
public final class ManualTicks {

   private static volatile boolean manual;

   /**
    * The server loop's own manager, captured from {@code Server.frameTick}.
    *
    * <p>Needed because the verbs that switch modes have no way to reach it: the loop is constructed in
    * {@code ServerLoader}, which lives in {@code Server.jar} and is not on a mod's usual path. The frame
    * patch is handed it every iteration, so the first frame after boot supplies it.
    */
   private static volatile TickManager loop;

   /** Ticks a test has granted and the server has not yet spent. */
   private static final AtomicLong BUDGET = new AtomicLong();

   /** What to put back on returning to automatic ticking. */
   private static volatile int autoMaxFPS = 20;

   /**
    * Frames a second while ticks are being granted explicitly.
    *
    * <p>This is raised, rather than the game's time modifier, and the difference is not cosmetic --
    * inflating the modifier was the first attempt and it broke the suite in a way that took a while to
    * attribute. {@code Server.frameTick} runs every iteration and calls {@code tickMovement} with
    * {@code tickManager.getDelta()}, and that delta is multiplied by {@code globalTimeMod}. Set the modifier
    * to ten thousand and every frame integrates movement with a delta pinned at its 100ms ceiling, a
    * thousand times a second: the synthetic player moves at a hundred times its proper speed. Tests then
    * fail intermittently and in different places each run, because the player is somewhere unexpected --
    * which is what was actually happening, and it read as flakiness rather than as a bug of mine.
    *
    * <p>Raising {@code maxFPS} instead leaves the delta honest. Frames become finer-grained, each carrying a
    * millisecond of movement rather than fifty, and everything integrating over delta still advances at the
    * correct rate. It also keeps the loop paced, so no sleep is needed in the tick gate and no core is spun.
    *
    * <p>Why fast frames matter at all when ticks are granted synchronously: the frame is where queued verbs
    * are drained ({@link ServerFrameTickPatch}), so the frame rate is the ceiling on command latency. At the
    * server's usual twenty frames a second that ceiling is 50ms, which is what a command used to cost.
    */
   public static final int DEFAULT_MANUAL_FPS = 1_000;

   private ManualTicks() {
   }

   public static boolean isManual() {
      return manual;
   }

   public static long remaining() {
      return BUDGET.get();
   }

   /** Called every frame by {@link ServerFrameTickPatch}, purely to learn which loop to configure. */
   public static void observeLoop(TickManager tickManager) {
      loop = tickManager;
   }

   /**
    * The server loop's own {@link TickManager}, or null before the first frame.
    *
    * <p>Exposed so the {@code clocks} query can read the engine's own counters rather than the harness
    * keeping a parallel set. The engine already tracks total ticks, expected ticks, skipped ticks and total
    * frames, and those are the numbers that show how far the loop has drifted from the granted budget --
    * which is invisible from Python otherwise, and was the reason the lockstep gap stayed an argument
    * instead of a measurement.
    */
   public static TickManager loopTickManager() {
      return loop;
   }

   /**
    * Switches to granting ticks explicitly.
    *
    * @return false if the server loop has not been seen yet, in which case nothing changed and the caller
    *     should say so rather than report a mode that is not in force
    */
   public static boolean enable(int frames) {
      TickManager tickManager = loop;
      if (tickManager == null) {
         return false;
      }

      if (!manual) {
         autoMaxFPS = tickManager.getMaxFPS();
         manual = true;
      }

      // Re-applied rather than only set once: anything else that touches the frame rate -- another mod, or
      // the game's own debug key -- would otherwise leave manual mode quietly half-configured, which reads
      // as "commands are mysteriously slow" rather than as a conflict.
      tickManager.setMaxFPS(frames > 0 ? frames : DEFAULT_MANUAL_FPS);
      return true;
   }

   /** Returns to the clock, restoring what was in force before. Idempotent. */
   public static boolean disable() {
      TickManager tickManager = loop;
      if (tickManager == null) {
         return false;
      }

      if (manual) {
         manual = false;
         BUDGET.set(0L);
         FRAMES.set(0L);
         tickManager.setMaxFPS(autoMaxFPS);
      }

      return true;
   }

   /**
    * Grants {@code count} game ticks.
    *
    * <p>The {@code tick} verb grants and then spends them immediately, in the same command. The budget is
    * still what {@link ServerTickPatch} checks, so this is what distinguishes a tick a test asked for from
    * one the clock would have run by itself -- but nothing waits for the loop to notice.
    */
   public static void grant(long count) {
      if (count > 0L) {
         BUDGET.addAndGet(count);
      }
   }

   /** Discards any unspent grant, so a failed run of ticks cannot leak into the next test. */
   public static void clearBudget() {
      BUDGET.set(0L);
      FRAMES.set(0L);
   }

   /**
    * The frame half of the budget, and the reason it exists.
    *
    * <p>{@code Server.frameTick} is deliberately *not* gated -- see {@link ServerFrameTickPatch} -- because
    * it is where harness commands are drained and where packets are processed, so freezing it deadlocks the
    * server. But its second half advances game state: {@code World.frameTick} runs the world clock and every
    * level's entity movement. Leaving that on the loop's schedule made identical work advance it a variable
    * number of times -- measured at 27 to 78 frame ticks for the same command sequence, a threefold spread
    * against a granted-tick count that never varied at all.
    *
    * <p>So the split is: packets and the command queue keep running every loop iteration, while
    * {@code World.frameTick} is budgeted exactly like a game tick. {@link WorldFrameTickPatch} claims from
    * here.
    */
   private static final AtomicLong FRAMES = new AtomicLong();

   /** How many world frame ticks have actually run, for {@code query clocks} to compare against granted ticks. */
   private static final AtomicLong FRAMES_RUN = new AtomicLong();

   /** @see #FRAMES */
   public static long framesRun() {
      return FRAMES_RUN.get();
   }

   /**
    * Called from {@link WorldFrameTickPatch} to decide whether the world clock and entity movement advance.
    *
    * @return true to run the world frame tick, false to skip it
    */
   public static boolean claimFrame() {
      if (!manual) {
         FRAMES_RUN.incrementAndGet();
         return true;
      }

      while (true) {
         long current = FRAMES.get();
         if (current <= 0L) {
            return false;
         }

         if (FRAMES.compareAndSet(current, current - 1L)) {
            FRAMES_RUN.incrementAndGet();
            return true;
         }
      }
   }

   /**
    * Runs one world frame tick immediately, the way the server loop would have.
    *
    * <p>Called by the {@code tick} verb after each granted tick, because the real loop alternates the two:
    * {@code ServerGameLoop.update} runs {@code server.tick()} and then {@code server.frameTick(this)} on the
    * same iteration. Before this, a burst of N ticks ran back to back with no frame tick between any of them,
    * so movement was never integrated mid-burst and the world clock did not move at all during a settle --
    * which is not what the same N ticks do in a real server.
    *
    * <p>{@code world.frameTick} is called rather than {@code server.frameTick} on purpose. The latter would
    * re-enter {@link ServerFrameTickPatch}'s exit advice and drain the command queue from inside a command
    * already being drained, which is a recursion this has no need to risk. What is skipped by going direct is
    * packet processing -- already done every loop iteration -- and each client's {@code tickMovement}, which
    * for a synthetic player with no input is inert.
    *
    * @return false if the loop has not been seen yet, so the caller can say so rather than silently skip
    */
   public static boolean runFrame(Server server) {
      TickManager tickManager = loop;
      if (tickManager == null || server == null || server.world == null) {
         return false;
      }

      FRAMES.incrementAndGet();
      fixedDeltaThread = Thread.currentThread();
      try {
         server.world.frameTick(tickManager);
      } finally {
         fixedDeltaThread = null;
      }

      return true;
   }

   /**
    * The thread currently inside {@link #runFrame}, or null.
    *
    * <p>Fix one gave the world frame tick a deterministic *count*; this gives it a deterministic *size*.
    * Everything downstream scales by {@code TickManager}'s delta, which is
    * {@code (nanoTime - loopTime) / 1e6 * globalTimeMod} -- so a frame tick invoked from a tight burst of
    * granted ticks measured microseconds, and the world clock effectively stopped: 0.0 world-ms per granted
    * tick where a real server advances 50.
    *
    * <p><b>Scoped to a thread rather than pinned globally, and that is not fussiness.</b>
    * {@code Server.frameTick} calls {@code getClient(i).tickMovement(tickManager.getDelta())} on *every*
    * unpaced loop iteration, outside this gate. Pinning the accessor globally would therefore advance client
    * movement by a full tick per loop iteration -- hundreds of times a second, faster and no less wrong than
    * what it replaced. Only the window this class opens deliberately gets the fixed value.
    *
    * <p>A plain field is enough because {@link #runFrame} is synchronous on the server thread, and comparing
    * the calling thread means work handed to {@code Level.executor()} mid-frame cannot pick the value up by
    * accident.
    *
    * @see TickManagerDeltaPatch
    */
   private static volatile Thread fixedDeltaThread;

   /**
    * Whether the caller is inside a harness-driven world frame tick and should see one tick's worth of time.
    *
    * <p>Deliberately ignores {@code globalTimeMod}: under manual ticks a granted tick *is* a tick, and a test
    * that wants more game time grants more ticks rather than scaling each one. Timescale remains the
    * clock-mode knob it always was.
    */
   public static boolean isFixedDelta() {
      return manual && Thread.currentThread() == fixedDeltaThread;
   }

   /**
    * Called from the tick patch to decide whether a game tick happens.
    *
    * <p>No sleeping here: the loop is still paced by {@code maxFPS}, so an ungranted iteration costs nothing
    * beyond the frame the engine was going to run anyway.
    *
    * @return true to run the tick, false to skip it
    */
   public static boolean claimTick() {
      if (!manual) {
         return true;
      }

      while (true) {
         long current = BUDGET.get();
         if (current <= 0L) {
            return false;
         }

         if (BUDGET.compareAndSet(current, current - 1L)) {
            return true;
         }
      }
   }
}
