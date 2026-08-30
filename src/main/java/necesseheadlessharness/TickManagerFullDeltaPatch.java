package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * The world clock's half of the fixed delta. See {@link TickManagerDeltaPatch} for the reasoning; only the
 * accessor differs.
 *
 * <p>{@code fullDelta} is the unclamped millisecond delta, and it is what {@code WorldEntity.tickTime} scales
 * by {@code worldTimeMod} and {@code timeMod} to advance {@code worldTime} and {@code time}. With the frame
 * tick now driven from a tight burst of granted ticks, the measured value was microseconds and the world clock
 * had effectively stopped -- 0.0 world-ms per granted tick, against 50 on a real server. Returning
 * {@code msPerTick} here is what makes a granted tick mean fifty milliseconds of world time.
 *
 * <p>Two patch classes rather than one because {@code @ModMethodPatch} binds a single method signature, and
 * because nested classes are not a documented target for the loader's annotation scan.
 */
@ModMethodPatch(target = TickManager.class, name = "getFullDelta", arguments = {})
public class TickManagerFullDeltaPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) float returned) {
      if (ManualTicks.isFixedDelta()) {
         returned = (float)TickManager.msPerTick;
      }
   }
}
