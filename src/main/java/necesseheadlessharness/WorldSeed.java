package necesseheadlessharness;

/**
 * The world seed the harness generates worlds with, so that a regenerated world is the <em>same</em> world.
 *
 * <p><b>Why this exists.</b> A fresh session deletes the world archive and lets the server generate a new one,
 * which is what keeps one run's placed objects out of the next run's way. But
 * {@code ServerCreationSettings.worldSeed} defaults to {@code getNewRandomSpawnSeed()} -- five random characters
 * -- so every run generated a <em>different</em> world. Nothing asked for that; it is simply the default a
 * player wants when starting a new save.
 *
 * <p>The consequence was not subtle, and it defeated the tick-budget work rather than sitting alongside it. The
 * spawn island is fixed and the nominal spawn tile is always (512,512), but {@code SpawnTileFinder} then
 * searches the generated terrain for somewhere valid to stand -- so a new seed moved spawn to 680,472, then
 * -1048,8, then -696,-840 on three consecutive runs. Every test addresses tiles as spawn plus an offset, so the
 * entire fixture layout landed on new terrain, in a new biome, with a different set of nearby mobs feeding the
 * one scheduler still outside the tick budget. Identical tick counts on a non-identical world is not
 * reproducibility, and five runs happening to agree is not evidence that it is.
 *
 * <p><b>Set through a system property rather than a command,</b> because world generation happens during boot,
 * before the command queue exists. There is no moment at which a harness verb could pin it in time.
 *
 * <p>Unpinned means unchanged: an empty or absent property leaves the engine's random seed alone, which is the
 * opt-in for deliberately varying terrain. Pinning trades intermittent discovery of terrain-dependent bugs for
 * consistent blindness to them, so the varied mode has to remain reachable to be an honest default.
 */
public final class WorldSeed {

   /** The system property carrying the seed. Empty or absent leaves generation random. */
   public static final String PROPERTY = "necesseheadlessharness.worldseed";

   /**
    * Read once at class load. The property is passed with {@code -D} on the command line, so it is present
    * before any of this runs, and it cannot change while the JVM lives.
    */
   private static final String PINNED = System.getProperty(PROPERTY, "").trim();

   private WorldSeed() {
   }

   /**
    * Whether generation should use a fixed seed.
    *
    * <p>Public, like everything an {@code @Advice} body touches: ByteBuddy inlines the advice into the target
    * class, so the call is resolved with <em>that</em> class's access rights. A package-private member here
    * fails at runtime with {@code IllegalAccessError}, and only once the patched method is first called.
    */
   public static boolean isPinned() {
      return !PINNED.isEmpty();
   }

   /** The seed to generate with. Only meaningful when {@link #isPinned()}. */
   public static String pinned() {
      return PINNED;
   }
}
