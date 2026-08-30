package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * The predicate form of {@code getTick() == 1}, kept consistent with {@link TickManagerTickInSecondPatch}.
 *
 * <p>See {@link GameClock} for why the engine's wall-clock counter is wrong under manual ticks, which engine
 * behaviour depends on it, and why replacing it is safe. Inert outside manual mode.
 */
@ModMethodPatch(target = TickManager.class, name = "isFirstGameTickInSecond", arguments = {})
public class TickManagerFirstTickInSecondPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) boolean returned) {
      if (GameClock.overriding()) {
         returned = GameClock.isTickInSecond(1);
      }
   }
}
