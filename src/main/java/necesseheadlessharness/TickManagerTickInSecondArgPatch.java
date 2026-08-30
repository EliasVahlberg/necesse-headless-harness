package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * The parameterised form, used for staggering periodic work across the second.
 *
 * <p>See {@link GameClock} for why the engine's wall-clock counter is wrong under manual ticks, which engine
 * behaviour depends on it, and why replacing it is safe. Inert outside manual mode.
 */
@ModMethodPatch(target = TickManager.class, name = "isGameTickInSecond", arguments = {int.class})
public class TickManagerTickInSecondArgPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Argument(0) int tick, @Advice.Return(readOnly = false) boolean returned) {
      if (GameClock.overriding()) {
         returned = GameClock.isTickInSecond(tick);
      }
   }
}
