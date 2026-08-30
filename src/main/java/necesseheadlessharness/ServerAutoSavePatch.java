package necesseheadlessharness;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;

/**
 * Skips the engine's periodic autosave while a test has asked for it to be suppressed.
 *
 * <p>See {@link Autosave} for why suppression is wanted at all, and why it has to be a patch instead of a
 * setting. This is the mechanism and nothing else.
 *
 * <p>{@code tickAutoSave} is the target rather than {@code startFullSave}, which sounds like the more direct
 * choice and is the wrong one: {@code startFullSave} is also how a clean shutdown and an explicit save run, and
 * blocking those would break persistence across a restart -- the one thing that genuinely needs a save to
 * happen. Patching the periodic trigger leaves every deliberate save alone.
 *
 * <p>Nothing happens unless a test asks: {@link Autosave#isSuppressed()} is false until a harness command sets
 * it, so an unused harness leaves the server's save behaviour exactly as it found it, and a client process --
 * where the harness is dormant -- never sets it at all.
 */
@ModMethodPatch(target = Server.class, name = "tickAutoSave", arguments = {})
public class ServerAutoSavePatch {

   @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
   static boolean onEnter() {
      // Truthy plus skipOn means "skip the original", so this reads directly rather than inverted.
      return Autosave.isSuppressed();
   }
}
