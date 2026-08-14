package necesseheadlessharness;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.level.maps.Level;
import net.bytebuddy.asm.Advice;

/**
 * Drains {@link ServerThreadTasks} at the end of every server tick, giving the mod a guaranteed
 * server-thread execution point.
 *
 * <p>Patching is a last resort here rather than a first instinct. The engine's tidier extension
 * points were checked first and none of them fit: {@code WorldData} and {@code LevelData} both offer
 * a {@code tick()} on the server thread, but each is only instantiated when loaded from save data, so
 * neither exists on a freshly generated world — exactly the case the harness uses. An object entity
 * ticks server-side too, but the command that *places* the first object is itself the work needing a
 * thread, so that is circular.
 *
 * <p>{@code Level.serverTick()} is a stable, zero-argument core method, and two subclasses override
 * it — {@code AscendedVoidLevel} and {@code DeepCaveLevel} — but the patch binds to the declaration
 * in {@code Level}, which they call through. The queue is global and idempotent, so it does not
 * matter which level drains it, or how many do.
 *
 * <p><b>The drain has moved to {@link ServerFrameTickPatch}</b>, and this patch now only counts ticks.
 * Manual tick mode skips {@code Server.tick()} entirely, so anything draining from inside it stops being
 * reached -- including the verb that would grant the next tick. Counting still belongs here, because
 * {@code Level.serverTick} is precisely what a test means by "a tick passed": it is the thing being
 * withheld, so counting its invocations reports game time under either mode without special-casing either.
 *
 * <p>If this patch ever fails to apply, nothing silently degrades: the command reports that the
 * server thread never picked the work up, rather than appearing to succeed.
 */
@ModMethodPatch(target = Level.class, name = "serverTick", arguments = {})
public class LevelServerTickPatch {
   @Advice.OnMethodExit
   static void onExit(@Advice.This Level level) {
      Ticks.onLevelTick(level);
   }
}
