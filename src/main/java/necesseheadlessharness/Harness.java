package necesseheadlessharness;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import necesse.engine.GameLaunch;
import necesse.engine.GlobalData;
import necesseheadlessharness.command.TestVerb;

/**
 * The harness's public API. A consumer mod calls this from {@code postInit()}.
 *
 * <p>Registration is deliberately not a registry in the game's sense: the game's registries close
 * after {@code init()} and throw afterwards, whereas a test verb has no wire format, no ID space
 * and nothing to synchronise, so there is no reason to make it that strict.
 *
 * <p><strong>The soft-dependency trap, and how the harness now handles it for you.</strong> A class that implements
 * {@link TestVerb} cannot sit in a released mod jar. {@code LoadedMod.loadClasses} defines every {@code .class} entry
 * as the mod loads, defining a class resolves its superclass and interfaces, and with the harness absent that becomes
 * a fatal {@code ModLoadException} -- so the mod refuses to load for every player, before any of its own code runs and
 * with no call site left to guard. An earlier version of this javadoc concluded from that you should keep such classes
 * out of your jar and build a second one for testing. That works, but it means the jar your tests exercise is not the
 * jar anyone runs.
 *
 * <p>Only <em>supertypes</em> are eager, though. Ship your bridge classes renamed to {@code .classdata} under
 * {@code harnessbridge/} and the mod loader never treats them as classes at all; {@link ModBridges} defines them when
 * the harness is running and calls your {@code register()}. One jar, tested as shipped, and a player can install the
 * harness on the world that is misbehaving. See {@link ModBridges} for what to ship and the one thing a bridge may not
 * contain.
 *
 * <p>Nothing else is required of you: no dependency declaration, and no load-order relationship, because discovery
 * runs from the harness's own {@code postInit}, by which point the engine has run every mod's {@code init}.
 */
public final class Harness {

   /**
    * The request/reply protocol's version, reported by {@code hello}.
    *
    * <p>Bump it when a reply's shape changes in a way a driver would notice. The Python client
    * refuses a version it does not know, because the alternative is a client misreading replies
    * from a jar it does not match -- and a stale installed jar is a mistake this project has
    * already made once.
    */
   public static final int PROTOCOL_VERSION = 1;

   private static final Map<String, TestVerb> VERBS = new LinkedHashMap<>();

   private static final Map<String, TestVerb> EXPECTATIONS = new LinkedHashMap<>();

   /** Short name in a scenario -> the real object string ID. */
   private static final Map<String, String> OBJECT_ALIASES = new HashMap<>();

   private Harness() {
   }

   /**
    * Whether this process is a dedicated server, and so whether the harness should do anything at
    * all.
    *
    * <p>{@code GlobalData.isServer} is set at the top of {@code loadAll}, before {@code loadMods},
    * so it is already correct by the time any mod method runs. A client's singleplayer server does
    * not count: that runs inside a client process, where a testing tool has no business acting.
    */
   public static boolean isHeadlessServer() {
      return GlobalData.isServer();
   }

   /**
    * The launch option that turns the harness on inside a client: {@code -harness}.
    *
    * <p>Exists because being dormant in a client cost something real. The command is an
    * {@code OWNER}-level {@link necesse.engine.commands.ChatCommand}, so before the gate it could be
    * typed in one's own singleplayer world -- {@code /harness fill 1 0 stone 2000} sets up a scene for
    * visual QA in one line, where doing it by hand in creative takes minutes. Off by default, because
    * the default has to be safe; available when asked for, because the capability is worth having.
    */
   public static final String CLIENT_OPT_IN = "harness";

   /**
    * Whether the harness should act at all: a dedicated server always, a client only when launched
    * with {@code -harness}.
    */
   public static boolean isActive() {
      return isHeadlessServer() || GameLaunch.launchOptions.containsKey(CLIENT_OPT_IN);
   }

   /**
    * Adds a verb. Replacing one the harness ships is allowed and not warned about: overriding a
    * generic verb with a mod-aware version is a legitimate thing to want.
    */
   public static void registerVerb(TestVerb verb) {
      VERBS.put(verb.name().toLowerCase(), verb);
   }

   /**
    * Lets a scenario say {@code place unit 5 0} instead of naming the full string ID every time.
    *
    * <p>Worth doing for more than brevity: a scenario is read far more often than it is written,
    * and short names keep the assertion visible rather than buried in identifiers.
    */
   public static void registerObjectAlias(String alias, String objectStringID) {
      OBJECT_ALIASES.put(alias.toLowerCase(), objectStringID);
   }

   public static String resolveObject(String nameOrAlias) {
      String alias = OBJECT_ALIASES.get(nameOrAlias.toLowerCase());
      return alias != null ? alias : nameOrAlias;
   }

   /**
    * Adds a kind to the {@code expect} verb, so a mod's assertions read as
    * {@code expect capacity 0 0 40 80} rather than needing a verb of their own.
    *
    * <p>Registering a kind the harness ships replaces it, which is intended: the built-in
    * {@code item} counts what is in the inventory at a tile, and a mod that aggregates across
    * containers means something different by the same word.
    */
   public static void registerExpectation(TestVerb kind) {
      EXPECTATIONS.put(kind.name().toLowerCase(), kind);
   }

   public static TestVerb expectation(String kind) {
      return EXPECTATIONS.get(kind.toLowerCase());
   }

   public static Iterable<String> expectationKinds() {
      return EXPECTATIONS.keySet();
   }

   public static TestVerb verb(String name) {
      return VERBS.get(name.toLowerCase());
   }

   public static Iterable<String> verbNames() {
      return VERBS.keySet();
   }
}
