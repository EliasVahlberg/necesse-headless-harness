package necesseheadlessharness;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.ServerCreationSettings;
import necesse.engine.util.GameRandom;
import net.bytebuddy.asm.Advice;

/**
 * Makes world generation use the harness's fixed seed, so a regenerated world is the same world every run.
 *
 * <p><b>The one-argument overload is the target on purpose.</b> {@code getNewRandomSpawnSeed()} delegates to
 * {@code getNewRandomSpawnSeed(GameRandom)}, so patching the latter covers both the no-argument path -- which is
 * what the {@code worldSeed} field initialiser uses -- and any caller that supplies its own random. Patching the
 * no-argument form instead would leave the direct callers unpinned.
 *
 * <p>A patch is needed rather than a constructor argument because the seed is chosen by a field initialiser on
 * {@code ServerCreationSettings}, during a boot the harness does not participate in. {@code ServerCreationSettings}
 * does expose a constructor taking an explicit seed, so a fixed seed is a supported configuration of the engine
 * and not a behaviour being forced on it -- the harness simply has no way to reach that constructor from outside
 * the dedicated server's launch path.
 *
 * @see WorldSeed for why the seed arrives as a system property, and why every member it exposes is public
 */
@ModMethodPatch(
   target = ServerCreationSettings.class,
   name = "getNewRandomSpawnSeed",
   arguments = {GameRandom.class}
)
public class WorldSeedPatch {

   /**
    * Replaces the generated seed with the pinned one. Deliberately still lets the original run: it consumes one
    * value from the random it was handed, and skipping that would shift every later draw from the same generator.
    */
   @Advice.OnMethodExit
   static void onExit(@Advice.Return(readOnly = false) String returned) {
      if (WorldSeed.isPinned()) {
         returned = WorldSeed.pinned();
      }
   }
}
