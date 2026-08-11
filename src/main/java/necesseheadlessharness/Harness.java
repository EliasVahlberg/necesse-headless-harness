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
 * <p><strong>The soft-dependency trap, and the advice that does not work.</strong> An earlier
 * version of this javadoc said to isolate your registration in one class and call it from a
 * try/catch. That is wrong. {@code LoadedMod.loadClasses} defines every class in a mod jar as the
 * mod loads and turns a {@code LinkageError} into a fatal {@code ModLoadException}, so one class
 * referencing these types stops your mod loading at all for anyone without the harness -- before
 * any of your code runs, leaving no call site to guard.
 *
 * <p>Keep your harness-facing classes out of your released jar instead; test code should not ship
 * anyway. Exclude the package from your normal jar and build a second one for testing. A
 * try/catch around your registration call is still worth having, because with the classes excluded
 * that is the path actually taken. Verified by booting a dedicated server with the harness removed
 * from the mods folder.
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
