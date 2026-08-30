package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * Reports the granted tick's position in the game second, which is what {@code getTick() == 1} is asking.
 *
 * <p>See {@link GameClock} for why the engine's wall-clock counter is wrong under manual ticks, which engine
 * behaviour depends on it, and why replacing it is safe. Inert outside manual mode.
 */
@ModMethodPatch(target = TickManager.class, name = "getTick", arguments = {})
public class TickManagerTickInSecondPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) int returned) {
      if (GameClock.overriding()) {
         returned = GameClock.tickInSecond();
      }
   }
}
