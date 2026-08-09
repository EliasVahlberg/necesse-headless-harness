package necessetestkit.command;

/**
 * A verb a mod adds to the kit's command.
 *
 * <p>The kit ships verbs that address anything by string ID, which covers a surprising amount:
 * placing and breaking objects, filling inventories, giving items, clicking slots, and asserting
 * item counts. What it cannot know is a mod's own concepts -- so a mod registers those itself.
 *
 * <p>Implementations run on the server thread with their regions already loaded. They should not
 * spawn threads, and should not assume a player exists unless {@link #needsPlayer()} says so.
 */
public interface TestVerb {

   /** The word that selects this verb, lowercase, e.g. {@code capacity}. */
   String name();

   /** One line, shown when the verb is used wrongly. Include the argument layout. */
   String usage();

   boolean run(TestContext context);

   /**
    * The argument index at which this verb's {@code (dx, dy)} pair begins, counting the verb
    * itself as index 0. Return -1 if the verb addresses no tile.
    *
    * <p>This exists because the kit loads the regions a verb addresses before running it, and
    * getting it wrong is not harmless. An earlier version scanned every argument for integers and
    * treated each consecutive pair as a coordinate, so {@code expect capacity 0 0 2560 2560} read
    * the two slot counts as a coordinate and tried to load a region 2560 tiles away. That region
    * had never been generated, so the load took the generation path and deadlocked the server.
    * Point at real coordinates only.
    */
   default int coordinateArgIndex() {
      return -1;
   }

   /**
    * Whether the verb needs a player. If true and nobody is connected, the kit reports that
    * rather than letting the verb dereference a null client.
    */
   default boolean needsPlayer() {
      return false;
   }
}
