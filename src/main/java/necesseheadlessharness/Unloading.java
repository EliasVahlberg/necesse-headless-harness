package necesseheadlessharness;

import necesse.engine.Settings;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.util.LevelIdentifier;
import necesse.level.maps.Level;
import necesse.level.maps.regionSystem.Region;

/**
 * Brings level and region loading under a test's control instead of a timer's.
 *
 * <p><b>Why this exists.</b> Necesse drops what nobody is near: a {@link Level} whose {@code unloadLevelBuffer}
 * passes {@code Settings.unloadLevelsCooldown} is saved and unloaded by {@code Server}, and independently of that
 * every {@link Region} carries a {@code RegionUnloadBuffer} and goes on its own schedule from
 * {@code LevelManager.serverTick}. <b>Object entities live in regions</b>, so a chest, station or machine can be
 * absent from memory while the level holding it is fully loaded -- which means any mod that reaches a tile no
 * player is standing near has two mechanisms to satisfy and will meet them in the order that hurts.
 *
 * <p>That is not a hypothetical. The harness's first consumer shipped a wireless storage terminal that pinned the
 * level and not the region, and it took a human playing the game to find it: opening after a wait reported the
 * terminal missing, and a terminal left open closed itself half a minute in. No test could see it, because
 * reproducing it meant waiting thirty-one seconds of real game time for a sweep the suite had no way to trigger.
 * A test suite that runs in milliseconds cannot observe a timer measured in tens of seconds, so it stops
 * observing this whole class of bug.
 *
 * <p><b>Both directions are needed, and they are different tools.</b> Forcing an unload makes the case reachable
 * at all. Suppressing the automatic one makes long tests trustworthy: a test that grants seven hundred ticks in
 * manual mode has crossed the sweep's threshold without any wall-clock time passing, so the world can be
 * dismantled underneath it for reasons that have nothing to do with what it is testing. That is the same hazard
 * {@link ManualTicks} documents from the other side -- accelerating time changes what happens between commands --
 * and the same answer applies: make it explicit rather than incidental.
 *
 * <p><b>Deliberately not offered: suppressing automatic loading.</b> It looks symmetrical and it is a trap. Loading
 * is on demand and load-bearing -- {@code World.getLevel} loads a level a player is walking onto, and
 * {@code getRegionByTile(x, y, true)} loads a region anything is reaching into -- so a flag that blocked it would
 * not freeze the world, it would make the engine's own paths fail in ways no real game can. What a test actually
 * wants there is to know whether something is loaded, which is a question, so it is answered by
 * {@code query region} and {@code query level} rather than by a switch.
 */
public final class Unloading {

   /**
    * A day, in seconds, and specifically not {@link Integer#MAX_VALUE}.
    *
    * <p>The engine computes its thresholds as {@code 20 * Math.max(2, cooldown)} in {@code Server} and
    * {@code seconds * 20} in {@code RegionUnloadBuffer.shouldUnload}. At {@code MAX_VALUE} both overflow to a
    * negative number, every buffer compares greater than it, and the flag intended to stop all unloading unloads
    * <b>everything on the next tick</b> -- the exact inversion of what it says. A day is far longer than any test
    * and nowhere near the overflow.
    */
   private static final int EFFECTIVELY_NEVER = 86400;

   /** The engine's own value, captured before it is first changed so that restoring means restoring. */
   private static Integer configured;

   private Unloading() {
   }

   public static boolean isAutomatic() {
      return configured == null;
   }

   /**
    * The cooldown the engine is using right now, which is the large value while the sweep is suppressed.
    *
    * <p>Deliberately the live setting rather than the configured one. A test reading a threshold wants to know
    * what will actually happen, and reporting the value that would apply if the flag were on made the harness's
    * own self-check fail -- correctly -- on its first run.
    */
   public static int cooldownSeconds() {
      return Settings.unloadLevelsCooldown;
   }

   /** The engine's configured value, which is what turning the sweep back on will restore. */
   public static int configuredCooldownSeconds() {
      return configured != null ? configured : Settings.unloadLevelsCooldown;
   }

   /**
    * Turns the engine's two unload sweeps off, or back on.
    *
    * <p>One setting drives both, which is the engine's arrangement rather than a simplification here:
    * {@code Server} uses {@code unloadLevelsCooldown} for levels and {@code LevelManager.serverTick} passes
    * {@code max(cooldown, 2) + 1} to the region sweep. Raising it does not touch the buffers themselves, so
    * turning it back on resumes from wherever they had counted to rather than granting a fresh grace period.
    */
   public static void setAutomatic(boolean automatic) {
      if (automatic) {
         if (configured != null) {
            Settings.unloadLevelsCooldown = configured;
            configured = null;
         }
      } else if (configured == null) {
         configured = Settings.unloadLevelsCooldown;
         Settings.unloadLevelsCooldown = EFFECTIVELY_NEVER;
      }
   }

   /**
    * Unloads the region holding a tile, now, saving it as the engine's own sweep would.
    *
    * <p>{@code RegionManager.unloadRegion} is the whole idiom -- it notifies the region, removes it, hands it to
    * the files manager and disposes it, all under the entity manager's lock -- so this is the engine's own path
    * taken early rather than a synthetic teardown. Returns false when the region was not loaded to begin with,
    * which a test should usually treat as its setup having drifted rather than as success.
    */
   public static boolean unloadRegionAt(Server server, Level level, int tileX, int tileY) {
      // Coordinates derived from the tile, never read off a region object handed back by a lookup. The lookup's
      // one-entry cache survives removeRegion, so it can return a region that has been unloaded already -- and
      // taking regionX and regionY from that meant unloading a different region and reporting success, which
      // reads exactly like an unload that did not stick.
      int regionX = level.regionManager.getRegionCoordByTile(tileX);
      int regionY = level.regionManager.getRegionCoordByTile(tileY);
      if (!level.regionManager.isRegionLoaded(regionX, regionY)) {
         return false;
      }

      // Every connected client must forget the region first, or the unload is undone before the next command
      // arrives. ServerClient.tick walks its own loadedRegions calling getRegion(..., load) and then
      // keepLoaded() on each, so a region a client still claims is reloaded and pinned every tick -- which is
      // what happened on the first attempt at this, at every distance out to 640 tiles, with the buffer sitting
      // at zero to say so. A synthetic player never shrinks that set because a real one shrinks it by walking
      // away, and this is the same pair of steps the engine takes when it does: removeLoadedRegion sends the
      // client PacketUnloadRegion, then the region goes.
      for (int slot = 0; slot < server.getSlots(); slot++) {
         ServerClient client = server.getClient(slot);
         if (client != null) {
            client.removeLoadedRegion(level, regionX, regionY, true, true);
         }
      }

      return level.regionManager.unloadRegion(regionX, regionY);
   }

   /** Whether any client still claims a region, which is the reason an unload would not stick. */
   public static boolean claimedByAClient(Server server, Level level, int tileX, int tileY) {
      int regionX = level.regionManager.getRegionCoordByTile(tileX);
      int regionY = level.regionManager.getRegionCoordByTile(tileY);

      for (int slot = 0; slot < server.getSlots(); slot++) {
         ServerClient client = server.getClient(slot);
         if (client != null && client.hasRegionLoaded(level, regionX, regionY)) {
            return true;
         }
      }

      return false;
   }

   /**
    * Loads the region holding a tile, synchronously, generating it when there is no save file.
    *
    * <p>Both behaviours are the engine's. Generation is why this cannot be used to assert that a region exists:
    * it will always answer yes for any tile inside the level's bounds.
    */
   public static boolean loadRegionAt(Level level, int tileX, int tileY) {
      return level.regionManager.getRegionByTile(tileX, tileY, true) != null;
   }

   /**
    * Whether a tile's region is in memory.
    *
    * <p>{@code isTileLoaded} rather than a null check on {@code getRegionByTile(x, y, false)}, which is what this
    * was first written as and is <b>not</b> the same question. {@code RegionStructureDataMap} caches the last
    * region it was asked for and {@code removeRegion} does not invalidate that cache, so a lookup can hand back a
    * region that has already been unloaded and disposed. It made the harness's own probe report a never-visited
    * region 3200 tiles away as loaded, and it made an unload look as though it had been undone between commands.
    * {@code isTileLoaded} reads the map directly.
    */
   public static boolean isTileLoaded(Level level, int tileX, int tileY) {
      return level.regionManager.isTileLoaded(tileX, tileY);
   }

   /** The region object, when one is genuinely loaded, for reading its unload buffer. */
   public static Region loadedRegionAt(Level level, int tileX, int tileY) {
      return level.regionManager.isTileLoaded(tileX, tileY)
         ? level.regionManager.getRegionByTile(tileX, tileY, false)
         : null;
   }

   /**
    * Unloads a level, now, saving it first -- the same two calls {@code Server} makes when its own sweep fires.
    *
    * <p>Refuses the level a player is standing on, and the refusal is the useful part: {@code ServerClient.tick}
    * resolves its level every tick through {@code World.getLevel}, so unloading it underneath the player would be
    * undone immediately, and in the meantime the player's own mob belongs to an object the world no longer knows
    * about. There is no version of that which teaches a test anything.
    */
   public static boolean unloadLevel(Server server, Level level) {
      for (int slot = 0; slot < server.getSlots(); slot++) {
         if (server.getClient(slot) != null && server.getClient(slot).isSamePlace(level)) {
            return false;
         }
      }

      server.world.saveLevel(level);
      server.world.levelManager.unloadLevel(level);
      return true;
   }

   /** Whether a level is in memory, without loading it as {@code World.getLevel} would. */
   public static boolean isLevelLoaded(Server server, LevelIdentifier identifier) {
      return server.world.levelManager.getLevel(identifier) != null;
   }
}
