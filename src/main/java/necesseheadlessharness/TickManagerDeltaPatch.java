package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import net.bytebuddy.asm.Advice;

/**
 * Makes one granted tick worth exactly one tick of movement time, instead of however long the loop took.
 *
 * <p>{@code TickManager.delta} is {@code (nanoTime - loopTime) / 1e6 * globalTimeMod} in milliseconds, clamped
 * at 100. That is a wall-clock measurement: correct for a server running in real time, wrong for one whose
 * game logic only advances when a test says so. {@link WorldFrameTickPatch} fixed how *often* the world
 * advances; this fixes by how *much*. The replacement is the engine's own {@code TickManager.msPerTick}.
 *
 * <p>This is the delta the movement path uses -- {@code Level.frameTick} hands the manager to its layers,
 * region manager and entity manager, and {@code TileEntityList.frameTick} integrates {@code tickMovement} from
 * it. {@link TickManagerFullDeltaPatch} covers the world clock's uncapped counterpart.
 *
 * <p><b>Applies only inside {@link ManualTicks#runFrame}, on that thread.</b> See
 * {@link ManualTicks#isFixedDelta()} for why pinning it globally would be a different bug rather than a fix:
 * {@code Server.frameTick} integrates client movement from this same accessor on every unpaced loop iteration,
 * outside the gate. Everywhere else -- all of clock mode, every client process -- the engine's value is
 * returned untouched.
 */
@ModMethodPatch(target = TickManager.class, name = "getDelta", arguments = {})
public class TickManagerDeltaPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) float returned) {
      if (ManualTicks.isFixedDelta()) {
         returned = (float)TickManager.msPerTick;
      }
   }
}
