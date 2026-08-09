package necessetestkit;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import necessetestkit.command.TestVerb;

/**
 * The kit's public API. A consumer mod calls this from {@code postInit()}.
 *
 * <p>Registration is deliberately not a registry in the game's sense: the game's registries close
 * after {@code init()} and throw afterwards, whereas a test verb has no wire format, no ID space
 * and nothing to synchronise, so there is no reason to make it that strict.
 *
 * <p><strong>The soft-dependency trap.</strong> If your mod declares the kit under
 * {@code optionalDependencies}, do not call this from a class that also holds your mod's normal
 * code. With the kit absent, merely loading a class that references these types throws
 * {@code NoClassDefFoundError}. Put your registration in a class of its own and call it from a
 * try/catch, so a player without the kit installed loses the tests and nothing else.
 */
public final class TestKit {

   private static final Map<String, TestVerb> VERBS = new LinkedHashMap<>();

   private static final Map<String, TestVerb> EXPECTATIONS = new LinkedHashMap<>();

   /** Short name in a scenario -> the real object string ID. */
   private static final Map<String, String> OBJECT_ALIASES = new HashMap<>();

   private TestKit() {
   }

   /**
    * Adds a verb. Replacing one the kit ships is allowed and not warned about: overriding a
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
    * <p>Registering a kind the kit ships replaces it, which is intended: the built-in
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
