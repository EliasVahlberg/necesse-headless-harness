package necesseheadlessharness;

import necesse.engine.gameLoop.tickManager.TickManager;
import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;

/**
 * Drains queued harness work once per frame rather than once per game tick.
 *
 * <p><b>This is what makes {@link ServerTickPatch} usable instead of a deadlock.</b> Harness verbs are
 * marshalled onto the server thread through {@link ServerThreadTasks}, and that queue used to be drained
 * from {@code Level.serverTick} -- which sits inside {@code Server.tick()}, the very thing manual mode
 * skips. The verb that grants the next tick would then be waiting for a tick to be run, and the server
 * would sit there answering nothing.
 *
 * <p>{@code Server.frameTick} is the right home because the engine already runs it unconditionally:
 * {@code ServerGameLoop.update} gates {@code server.tick()} behind {@code isGameTick()} and calls
 * {@code server.frameTick()} on every iteration. It is also where {@code packetManager.tickNetworkManager}
 * and the packet drain live, so a command that arrives while the world is frozen is received, executed and
 * answered entirely within frozen game time.
 *
 * <p>It is the server thread either way, so this is no less safe than draining from the level tick -- the
 * whole reason that queue exists is that console commands arrive on {@code ServerScanThread} and mutating
 * the level from there inverts lock orders against the tick. If anything this point is safer, being outside
 * any level's own tick.
 *
 * <p><b>The second job is a side benefit that turned out to matter as much as the first.</b> Because the
 * queue is drained per frame rather than per tick, command latency stops being bounded by the tick rate. It
 * was measured at 49.89ms per command -- one 20-tick-a-second tick each -- and a suite issuing a few
 * thousand commands paid minutes for that alone. In manual mode the loop is unpaced and idles at about a
 * millisecond per iteration, so the same commands cost roughly a fiftieth of what they did.
 *
 * <p>The {@code TickManager} argument is taken because it is the server loop's own, and reaching it is
 * otherwise awkward from a mod: the loop is constructed inside {@code ServerLoader}, which lives in
 * {@code Server.jar}. Handing it to {@link ManualTicks} here means the mode can configure the real loop
 * rather than guess at it.
 */
@ModMethodPatch(target = Server.class, name = "frameTick", arguments = {TickManager.class})
public class ServerFrameTickPatch {

   @Advice.OnMethodExit
   static void onExit(@Advice.Argument(0) TickManager tickManager) {
      ManualTicks.observeLoop(tickManager);
      ServerThreadTasks.drain();
   }
}
