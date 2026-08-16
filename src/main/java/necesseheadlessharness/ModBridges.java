package necesseheadlessharness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import necesse.engine.GameLog;
import necesse.engine.modLoader.LoadedMod;
import necesse.engine.modLoader.ModLoader;

/**
 * Finds and activates the harness bridges that other mods ship, so a mod can support harness debugging from the same
 * jar its players run.
 *
 * <p><strong>The problem this solves.</strong> A class that implements {@link necesseheadlessharness.command.TestVerb}
 * cannot sit in a released mod jar. {@code LoadedMod.loadClasses} calls {@code loadClass} on every {@code .class} entry
 * as the mod loads, and defining a class requires resolving its superclass and interfaces (JVMS 5.3.5). With the
 * harness absent that resolution fails, and the loader turns the {@code LinkageError} into a fatal
 * {@code ModLoadException} -- so the mod refuses to load for every player, before any of its own code runs.
 *
 * <p>Note how narrow that is. Only <em>supertypes</em> are eager. A harness type named inside a method body, in a
 * method signature, or as a lambda's target interface is resolved lazily and costs nothing until the code runs. So the
 * fatal case is inheritance specifically, and only inheritance needs hiding.
 *
 * <p><strong>How it is hidden.</strong> The mod ships its bridge classes renamed so they do not end in {@code .class}
 * -- {@code harnessbridge/<binary name>.classdata} -- which is the one test the mod loader applies before it treats an
 * entry as a class. To the game they are opaque resources. This class reads them back and defines them itself, only
 * when the harness is actually running.
 *
 * <p><strong>Why the classes keep their identity.</strong> The loader below is parent-first with the system class
 * loader as its parent, and every mod jar is appended to that loader's search by the game. So the bridge's references
 * to {@code TestVerb}, to the game, and to the consumer mod's own classes all resolve to the very same classes
 * everything else is using. Only the bridge classes themselves come from here, because only they are missing from the
 * system loader. Had this been a sibling loader instead, the verbs would have been handed a second, incompatible copy
 * of the mod they are supposed to inspect.
 *
 * <p><strong>What a consumer mod must do.</strong> Ship {@code harnessbridge/bridge.txt} naming one class per line,
 * each with a {@code public static void register()}, and ship that class and its nested classes as
 * {@code .classdata} under {@code harnessbridge/}. Nothing else: no dependency declaration, and no load-order
 * relationship, because this runs from the harness's own {@code postInit} and the engine has already run every mod's
 * {@code init} by then.
 *
 * <p>One limit worth stating: a deferred class cannot be a ByteBuddy patch class. Patches are applied while mods load,
 * which is long over by the time anything here runs. Bridges are for test verbs, not for patching.
 */
public final class ModBridges {

   /** Where a consumer mod's bridge lives inside its jar. */
   private static final String DIR = "harnessbridge/";

   private static final String MARKER = DIR + "bridge.txt";

   /** Deliberately not {@code .class}: that suffix is exactly what would make the mod loader define these eagerly. */
   private static final String SUFFIX = ".classdata";

   private ModBridges() {
   }

   /**
    * Activates every bridge found among the enabled mods.
    *
    * <p>A mod with no bridge is the normal case and says nothing. A mod whose bridge fails is reported and skipped:
    * one mod's broken test hook has no business stopping the harness from driving the others.
    */
   public static void loadAll() {
      for (LoadedMod mod : ModLoader.getEnabledMods()) {
         if (NecesseHeadlessHarness.MOD_ID.equals(mod.id)) {
            continue;
         }

         try {
            load(mod);
         } catch (Throwable failed) {
            // Throwable, not Exception: the interesting failures here are LinkageErrors, and they are Errors.
            GameLog.warn.println("Headless harness: the bridge in " + mod.id + " could not be loaded: " + failed);
            failed.printStackTrace();
         }
      }
   }

   private static void load(LoadedMod mod) throws Exception {
      // The mod's own JarFile is reopened rather than reused. The class loader needs it to stay readable for as long
      // as it might be asked for a class -- nested classes arrive lazily -- and the lifetime of the game's handle is
      // the game's business, not ours.
      JarFile jar = new JarFile(mod.jarFile.getName());
      List<String> entries = readMarker(jar);
      if (entries.isEmpty()) {
         jar.close();
         return;
      }

      ClassLoader loader = new BridgeClassLoader(jar, ClassLoader.getSystemClassLoader());
      for (String className : entries) {
         Class<?> bridge = Class.forName(className, true, loader);
         bridge.getMethod("register").invoke(null);
         GameLog.out.println("Headless harness: bridge " + className + " registered by " + mod.id);
      }
   }

   /** The entry class names a mod declares, or an empty list when it ships no bridge at all. */
   private static List<String> readMarker(JarFile jar) throws IOException {
      List<String> names = new ArrayList<>();
      JarEntry marker = jar.getJarEntry(MARKER);
      if (marker == null) {
         return names;
      }

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(marker), StandardCharsets.UTF_8))) {
         String line;
         while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
               names.add(trimmed);
            }
         }
      }

      return names;
   }

   /**
    * Defines the bridge classes a mod ships as resources, and nothing else.
    *
    * <p>{@code findClass} is only consulted after the parent has failed, which is the whole design: everything that
    * exists on the system class path keeps coming from there, and the only names that reach here are the ones
    * deliberately hidden from the mod loader.
    */
   private static final class BridgeClassLoader extends ClassLoader {

      private final JarFile jar;

      private BridgeClassLoader(JarFile jar, ClassLoader parent) {
         super(parent);
         this.jar = jar;
      }

      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
         JarEntry entry = this.jar.getJarEntry(DIR + name.replace('.', '/') + SUFFIX);
         if (entry == null) {
            throw new ClassNotFoundException(name);
         }

         try (InputStream in = this.jar.getInputStream(entry)) {
            byte[] bytes = readAll(in);
            return this.defineClass(name, bytes, 0, bytes.length);
         } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
         }
      }

      private static byte[] readAll(InputStream in) throws IOException {
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         byte[] buffer = new byte[8192];
         int read;
         while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
         }

         return out.toByteArray();
      }
   }
}
