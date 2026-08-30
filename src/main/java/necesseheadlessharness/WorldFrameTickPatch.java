package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.world.World;
import net.bytebuddy.asm.Advice;

/**
 * Puts the world clock and entity movement under the tick budget, which {@link ServerTickPatch} alone did not.
 *
 * <p><b>Why a second patch instead of gating {@code Server.frameTick}.</b> That method does two unrelated
 * jobs, in this order: it pumps the network -- {@code tickNetworkManager} and the packet drain -- and then, if
 * the server is not paused, advances game state via {@code world.frameTick} and each client's
 * {@code tickMovement}. Skipping the whole method freezes the network with it, and since the harness's own
 * command queue is drained from that same method (see {@link ServerFrameTickPatch}), doing so deadlocks the
 * server: the verb that would grant the next tick can no longer be received. Advice cannot skip half a body,
 * so the state half is gated at its own entry point instead.
 *
 * <p><b>What this fixes, measured.</b> Manual mode gated game logic and nothing else, so identical work
 * advanced the world a variable number of times: five repetitions of one command sequence ran 27, 38, 43, 63
 * and 78 frame ticks, while the granted-tick count was 110 every single time. Everything driven from here --
 * the world clock in {@code WorldEntity.serverFrameTick}, and every level's {@code TileEntityList.frameTick}
 * calling {@code tickMovement} on its entities -- therefore advanced by an amount that depended on how busy
 * the machine was. That is non-determinism by construction rather than by bug, and no amount of test-suite
 * hygiene removes it.
 *
 * <p><b>What it does not fix.</b> The *number* of world frame ticks becomes deterministic; their *size* does
 * not. {@code WorldEntity.tickTime} and {@code tickMovement} both scale by {@code TickManager}'s delta, which
 * is still derived from {@code System.nanoTime()}, so a movement integrated once may still cover a different
 * distance. Fixing that means overriding the delta itself and is a separate change.
 *
 * <p>Outside manual mode this is inert: {@link ManualTicks#claimFrame()} returns true immediately, so the
 * clock-driven loop behaves exactly as the engine intends.
 */
@ModMethodPatch(target = World.class, name = "frameTick", arguments = {TickManager.class})
public class WorldFrameTickPatch {

   @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
   static boolean onEnter() {
      // Truthy plus skipOn means "skip the original", so the sense is inverted against claimFrame.
      return !ManualTicks.claimFrame();
   }
}
