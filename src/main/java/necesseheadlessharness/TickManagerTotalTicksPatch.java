package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * Reports total granted ticks instead of ticks the wall clock has passed.
 *
 * <p>See {@link GameClock} for why the engine's wall-clock counter is wrong under manual ticks, which engine
 * behaviour depends on it, and why replacing it is safe. Inert outside manual mode.
 */
@ModMethodPatch(target = TickManager.class, name = "getTotalTicks", arguments = {})
public class TickManagerTotalTicksPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) long returned) {
      if (GameClock.overriding()) {
         returned = GameClock.totalTicks();
      }
   }
}
