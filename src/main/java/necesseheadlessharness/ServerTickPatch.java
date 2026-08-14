package necesseheadlessharness;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;

/**
 * Gates the server's game tick on a granted budget, so a test controls when the world advances.
 *
 * <p>See {@link ManualTicks} for why game time is detached from the wall clock at all. This is the half
 * that stops it: {@code Server.tick()} is the whole game tick -- levels, object entities, containers,
 * mobs, settlers -- and skipping it freezes all of them together, which is what makes the freeze
 * consistent rather than partial.
 *
 * <p>{@code Server.tick()} is chosen over {@code TickManager.isGameTick()}, which looks like the more
 * natural target, for two reasons. {@code ServerGameLoop.update} calls {@code isGameTick()} more than
 * once per iteration, so a patch there that consumed a budget would consume several per tick and would
 * have to be non-consuming and stateful to be correct. And {@code TickManager} is shared with the client's
 * loop, so patching it puts harness logic on the hot path of somebody's game, where this puts it on a
 * method a client process never calls.
 *
 * <p>Nothing happens unless a test asks for it: {@link ManualTicks#claimTick()} returns true immediately
 * while manual mode is off, so an unused harness leaves the server's timing exactly as it found it, and a
 * client process -- where the harness is dormant -- never enables it at all.
 *
 * <p><b>The frame loop must keep running for this to be usable rather than a hang</b>, and it does:
 * {@code update} calls {@code server.frameTick()} unconditionally, and that is where packet processing
 * lives. {@link ServerFrameTickPatch} drains queued harness work from the same place, so commands are
 * still served while the world is stopped. Freezing ticks without moving that drain deadlocks, because the
 * verb that would grant the next tick is itself waiting for a tick to be run.
 */
@ModMethodPatch(target = Server.class, name = "tick", arguments = {})
public class ServerTickPatch {

   @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
   static boolean onEnter() {
      // Truthy plus skipOn means "skip the original", so the sense is inverted against claimTick.
      return !ManualTicks.claimTick();
   }
}
