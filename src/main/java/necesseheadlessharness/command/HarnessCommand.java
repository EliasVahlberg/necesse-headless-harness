package necesseheadlessharness.command;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import necesse.engine.commands.AutoComplete;
import necesse.engine.commands.ChatCommand;
import necesseheadlessharness.HeadlessPlayer;
import necesseheadlessharness.Harness;
import necesseheadlessharness.ServerThreadTasks;
import necesse.engine.commands.CommandLog;
import necesse.engine.commands.ParsedCommand;
import necesse.engine.commands.PermissionLevel;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.registries.ObjectRegistry;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.registries.TileRegistry;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.entity.objectEntity.interfaces.OEInventory;
import necesse.inventory.Inventory;
import necesse.inventory.InventorySlot;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.container.Container;
import necesse.inventory.container.ContainerAction;
import necesse.inventory.container.ContainerActionResult;
import necesse.inventory.container.slots.ContainerSlot;
import necesse.level.gameObject.GameObject;
import necesse.level.maps.Level;
import necesse.level.maps.regionSystem.RegionManager;

/**
 * Test-harness command, driven by scenario files through a headless server.
 *
 * <p>Exists because driving the mod by hand is slow and unrepeatable. Every subcommand is
 * a single console line, so a scenario file is just a list of these and any prefix of one
 * can be pasted into a live server to investigate a failure.
 *
 * <p>Coordinates are <b>relative to the world spawn tile</b>, which keeps scenarios valid
 * across worlds and seeds rather than hardcoding tiles from one save.
 *
 * <p>Assertions print a line beginning {@code PASS} or {@code FAIL}; the runner counts
 * those and sets its exit status from them.
 *
 * <p>Scoped to {@code OWNER} so the server console (which is {@code SERVER}, above owner)
 * can run it non-interactively, and so it also works in a singleplayer session where
 * assertions that need a real player can be checked.
 */
public class HarnessCommand extends ChatCommand {

   /** Guards against a scenario file that calls {@code run} on itself. */
   private boolean running = false;

   public HarnessCommand() {
      super("harness", PermissionLevel.OWNER);
   }

   @Override
   public String getUsage() {
      // Left over from the extraction, this advertised reset, report, withdraw, deposit and
      // depositall -- which belong to Arcane Storage and are registered at runtime, not built in --
      // while omitting 'player', which is built in. Hand-maintaining this string is the actual bug;
      // it should be derived from the verb registry once built-ins are registered like extensions
      // are. Until then it at least has to be true.
      StringBuilder usage = new StringBuilder("<place|fill|clear|break|give|open|close|click"
         + "|quickstack|restock|expect|player|run|echo");
      for (String registered : Harness.verbNames()) {
         usage.append('|').append(registered);
      }

      return usage.append("> ...").toString();
   }

   @Override
   public String getAction() {
      return "Necesse headless test harness";
   }

   @Override
   public String getCurrentUsage(Client client, Server server, ServerClient serverClient, String[] args) {
      return this.getUsage();
   }

   @Override
   public List<AutoComplete> autocomplete(Client client, Server server, ServerClient serverClient, String[] args) {
      return Collections.emptyList();
   }

   @Override
   public boolean run(Client client, Server server, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (args.isEmpty()) {
         logs.add("FAIL usage: " + this.getUsage());
         return false;
      }

      Level level = server.world.getLevel(server.world.worldEntity.spawnLevelIdentifier);
      if (level == null) {
         logs.add("FAIL could not resolve the spawn level");
         return false;
      }

      Point spawn = server.world.worldEntity.spawnTile;
      String sub = args.get(0).toLowerCase();

      // 'run' and 'echo' do no level work of their own: run feeds its lines back through this
      // method, so each line marshals itself.
      if (sub.equals("run")) {
         return this.runScenario(server, serverClient, args, logs);
      }

      if (sub.equals("echo")) {
         logs.add(String.join(" ", args.subList(1, args.size())));
         return true;
      }

      // Everything else touches the level, so it runs on the server thread rather than on the
      // console's own thread. See ServerThreadTasks for why: mutating the level from the command
      // scanner races the tick, and the engine's ThreadFreezeMonitor kills the server when the two
      // take the same pair of locks in opposite orders. This replaces both an entityManager.lock
      // workaround and a per-command delay in the test runner, neither of which was a real fix.
      //
      // Region loading is inside the marshalled work deliberately: loading a region that has never
      // been generated is itself one of the operations that used to invert.
      // A console command has no client. If a headless player has been spawned, stand in for one
      // here -- once, before dispatch -- so that every verb below sees a client without any of
      // them having to know where it came from.
      ServerClient actingClient = serverClient != null ? serverClient : HeadlessPlayer.current();

      boolean[] result = new boolean[1];
      boolean ran = ServerThreadTasks.runAndWait(() -> {
         // Reads do NOT load regions: the object layer resolves a tile through RegionBoundsExecutor
         // with loadIfNotLoaded=false, so an unloaded region reads as *empty* rather than as itself.
         // Only a player normally triggers a load, and the harness has no player. A freshly
         // generated world hides this entirely, since generation leaves every region in memory --
         // it appears only after a restart, where a scenario would see an empty world and report a
         // persistence bug that does not exist.
         this.ensureRegionLoaded(level, spawn, args);
         result[0] = this.dispatch(sub, level, spawn, server, actingClient, args, logs);
      }, 15000L);

      if (!ran) {
         // Better to say the work never happened than to report a pass that was never executed.
         logs.add("FAIL the server thread did not run '" + sub + "' within 15s");
         return false;
      }

      return result[0];
   }

   private boolean dispatch(String sub, Level level, Point spawn, Server server, ServerClient serverClient,
                            ArrayList<String> args, CommandLog logs) {
      try {
         switch (sub) {
            case "place":
               return this.place(level, spawn, args, logs);
            case "fill":
               return this.fill(level, spawn, args, logs);
            case "break":
               return this.breakObject(level, spawn, args, logs);
            case "expect":
               return this.expect(level, spawn, server, serverClient, args, logs);
            case "give":
               return this.give(level, serverClient, args, logs);
            case "clear":
               return this.clear(level, spawn, args, logs);
            case "open":
               return this.open(level, spawn, serverClient, args, logs);
            case "close":
               return this.close(serverClient, logs);
            case "quickstack":
               return this.quickStack(serverClient, logs);
            case "restock":
               return this.restock(serverClient, logs);
            case "click":
               return this.click(serverClient, args, logs);
            case "run":
               return this.runScenario(server, serverClient, args, logs);
            case "player":
               return this.player(server, level, args, logs);
            default:
               TestVerb verb = Harness.verb(sub);
               if (verb == null) {
                  logs.add("FAIL unknown subcommand '" + sub + "'; usage: " + this.getUsage());
                  return false;
               }

               if (verb.needsPlayer() && this.requirePlayer(serverClient, logs, sub) == null) {
                  return false;
               }

               return verb.run(new TestContext(level, spawn, server, serverClient, args, logs));
         }
      } catch (IndexOutOfBoundsException e) {
         logs.add("FAIL missing arguments for '" + sub + "'");
         return false;
      } catch (NumberFormatException e) {
         logs.add("FAIL expected a number: " + e.getMessage());
         return false;
      }
   }

   /** {@code place <terminal|unit> <dx> <dy>} */
   private boolean place(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      String what = args.get(1).toLowerCase();
      int x = spawn.x + Integer.parseInt(args.get(2));
      int y = spawn.y + Integer.parseInt(args.get(3));

      String stringID = Harness.resolveObject(what);
      int objectID = ObjectRegistry.getObjectID(stringID);
      if (objectID <= 0) {
         logs.add("FAIL unknown object '" + what + "'"
            + (stringID.equals(what) ? "" : "' (alias for '" + stringID + "')")
            + "; pass an object string ID, or register an alias with Harness.registerObjectAlias");
         return false;
      }

      // setObject creates the object entity itself, so nothing else is needed here.
      level.setObject(x, y, objectID);
      logs.add("placed " + what + " at " + args.get(2) + "," + args.get(3));
      return true;
   }

   /** {@code fill <dx> <dy> <itemStringID> <amount>} — writes straight into free slots. */
   private boolean fill(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));
      String itemID = args.get(3);
      int amount = Integer.parseInt(args.get(4));

      Inventory inventory = this.inventoryAt(level, x, y);
      if (inventory == null) {
         logs.add("FAIL nothing with an inventory at " + args.get(1) + "," + args.get(2));
         return false;
      }

      InventoryItem template;
      try {
         template = new InventoryItem(itemID, 1);
      } catch (Exception e) {
         logs.add("FAIL unknown item '" + itemID + "'");
         return false;
      }

      // Deliberately setItem rather than addItem: addItem takes a PlayerMob, and the whole
      // point of this command is to work with no player connected.
      int stack = template.item.getStackSize();
      int remaining = amount;

      for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
         if (!inventory.isSlotClear(slot)) {
            continue;
         }

         int put = Math.min(remaining, stack);
         inventory.setItem(slot, new InventoryItem(itemID, put));
         remaining -= put;
      }

      if (remaining > 0) {
         logs.add("FAIL inventory at " + args.get(1) + "," + args.get(2)
            + " had no room for " + remaining + " of " + itemID);
         return false;
      }

      logs.add("filled " + args.get(1) + "," + args.get(2) + " with " + amount + " " + itemID);
      return true;
   }

   /** {@code break <dx> <dy>} — clears the tile, as destroying the object would. */
   private boolean breakObject(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));
      level.setObject(x, y, 0);
      logs.add("broke object at " + args.get(1) + "," + args.get(2));
      return true;
   }

   /**
    * {@code expect units <dx> <dy> <n>} — linked unit count for a terminal.
    * {@code expect item <dx> <dy> <itemStringID> <n>} — aggregated amount for a terminal.
    * {@code expect total <itemStringID> <n>} — amount across <b>every</b> unit on the level,
    * which is the conservation check: it does not care about topology, so it catches items
    * created or destroyed by any action.
    */
   private boolean expect(Level level, Point spawn, Server server, ServerClient serverClient,
                          ArrayList<String> args, CommandLog logs) {
      String kind = args.get(1).toLowerCase();

      // A registered expectation wins over a built-in of the same name, deliberately. The harness's
      // 'item' counts what is in the inventory at a tile, which is right for a chest and wrong
      // for anything that aggregates -- so a mod must be able to redefine it rather than being
      // forced to invent a differently-named assertion for the same idea.
      TestVerb registered = Harness.expectation(kind);
      if (registered != null) {
         if (registered.needsPlayer() && this.requirePlayer(serverClient, logs, "expect " + kind) == null) {
            return false;
         }

         return registered.run(new TestContext(level, spawn, server, serverClient, args, logs));
      }

      if ("total".equals(kind)) {
         String itemID = args.get(2);
         int wanted = Integer.parseInt(args.get(3));
         int actual = 0;

         // Every inventory on the level, not a chosen set: 'total' exists to catch an action that
         // creates or destroys items, and a scan narrower than the whole level would miss items
         // that moved somewhere unexpected -- which is the interesting failure.
         for (ObjectEntity entity : level.entityManager.objectEntities) {
            if (entity instanceof OEInventory && !entity.removed()) {
               actual += this.countIn(((OEInventory)entity).getInventory(), itemID);
            }
         }

         return this.check(logs, actual == wanted, "total " + itemID + " = " + wanted,
            "expected " + wanted + ", found " + actual);
      }

      if ("held".equals(kind)) {
         String itemID = args.get(2);
         int wanted = Integer.parseInt(args.get(3));
         if (this.requirePlayer(serverClient, logs, "expect held") == null) {
            return false;
         }

         int actual = this.countHeld(serverClient, itemID);
         return this.check(logs, actual == wanted, "held " + itemID + " = " + wanted,
            "expected " + wanted + ", found " + actual);
      }

      if ("item".equals(kind)) {
         int x = spawn.x + Integer.parseInt(args.get(2));
         int y = spawn.y + Integer.parseInt(args.get(3));
         String itemID = args.get(4);
         int wanted = Integer.parseInt(args.get(5));

         Inventory inventory = this.inventoryAt(level, x, y);
         if (inventory == null) {
            logs.add("FAIL nothing with an inventory at " + args.get(2) + "," + args.get(3));
            return false;
         }

         int actual = this.countIn(inventory, itemID);
         return this.check(logs, actual == wanted, "item " + itemID + " = " + wanted,
            "expected " + wanted + ", found " + actual);
      }

      StringBuilder known = new StringBuilder("'item', 'total', 'held'");
      for (String extra : Harness.expectationKinds()) {
         known.append(", '").append(extra).append("'");
      }

      logs.add("FAIL expect takes " + known + ", got '" + kind + "'");
      return false;
   }

   private int countIn(Inventory inventory, String itemID) {
      int total = 0;

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         InventoryItem item = inventory.getItem(slot);
         if (item != null && item.item.getStringID().equals(itemID)) {
            total += item.getAmount();
         }
      }

      return total;
   }

   private boolean check(CommandLog logs, boolean ok, String what, String detail) {
      logs.add((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
      return ok;
   }

   // ------------------------------------------------------------------------------------
   // Player-coupled subcommands.
   //
   // Container(client, uniqueSeed) reads client.playerMob.getInv(), so a container cannot
   // exist without a player. That is the harness's hard boundary: with nobody connected
   // there is nothing to open, so everything below needs a live session and reports a clear
   // failure when run from the server console, where serverClient is null.
   //
   // These do not fabricate a click. They call the exact methods the packet handlers call:
   // a click is Container.applyContainerAction(slot, action) per PacketContainerAction, and
   // a withdraw is WithdrawAction.executePacket(reader) per PacketContainerCustomAction. So
   // what is tested is the shipping path, not a parallel imitation of it.
   // ------------------------------------------------------------------------------------

   /** {@code player spawn} / {@code player despawn} -- see {@link HeadlessPlayer}. */
   private boolean player(Server server, Level level, ArrayList<String> args, CommandLog logs) {
      String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
      if (action.equals("spawn")) {
         return HeadlessPlayer.spawn(server, level, logs) != null;
      }
      if (action.equals("despawn")) {
         HeadlessPlayer.despawn(server, logs);
         return true;
      }
      logs.add("FAIL usage: player <spawn|despawn>");
      return false;
   }

   /**
    * The container the player currently has open, or null with a stated reason.
    *
    * <p>Typed as {@code Container} rather than anything narrower on purpose: quick stack and
    * restock are engine-level slot conventions ({@code QUICK_STACK_SLOT}, {@code RESTOCK_SLOT}),
    * so these verbs work against a vanilla chest as readily as against a modded container.
    */
   /**
    * {@code open <dx> <dy>} -- interacts with whatever is at a tile, as a player would.
    *
    * <p>Generic because the engine already is: {@code GameObject.interact} is how every openable
    * thing opens, and each object decides for itself what container that means. A vanilla chest
    * opens an {@code OEInventoryContainer}, a workstation opens its crafting container, and a
    * modded object opens whatever it registered -- none of which this verb needs to know.
    */
   private boolean open(Level level, Point spawn, ServerClient serverClient, ArrayList<String> args,
                        CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "open") == null) {
         return false;
      }

      int x = spawn.x + Integer.parseInt(args.get(1));
      int y = spawn.y + Integer.parseInt(args.get(2));

      GameObject object = level.getObject(x, y);
      if (object == null || object.getID() == 0) {
         logs.add("FAIL nothing to open at " + args.get(1) + "," + args.get(2));
         return false;
      }

      // Ask the object first. This distinguishes "you addressed something that does not open" from
      // "it should have opened and did not", which are different bugs -- the first is the
      // scenario's fault and the second is the mod's.
      if (!object.canInteract(level, x, y, serverClient.playerMob)) {
         logs.add("FAIL " + object.getStringID() + " at " + args.get(1) + "," + args.get(2)
            + " reports it cannot be interacted with");
         return false;
      }

      // Compare the container before and after rather than checking for any container at all: a
      // player always has one open, so 'a container exists' would pass even when interact did
      // nothing. A change of identity is the evidence that something actually opened.
      Container before = serverClient.getContainer();
      object.interact(level, x, y, serverClient.playerMob);
      Container after = serverClient.getContainer();

      if (after == before) {
         logs.add("FAIL interacting with " + object.getStringID() + " at " + args.get(1) + ","
            + args.get(2) + " opened nothing");
         return false;
      }

      logs.add("PASS opened " + object.getStringID() + " at " + args.get(1) + "," + args.get(2));
      return true;
   }

   private Container requireContainer(ServerClient serverClient, CommandLog logs, String sub) {
      if (this.requirePlayer(serverClient, logs, sub) == null) {
         return null;
      }

      Container container = serverClient.getContainer();
      if (container == null) {
         logs.add("FAIL '" + sub + "' needs an open container; open one first");
         return null;
      }

      return container;
   }

   private ServerClient requirePlayer(ServerClient serverClient, CommandLog logs, String sub) {
      if (serverClient != null) {
         return serverClient;
      }

      logs.add("FAIL '" + sub + "' needs a player: a container is built from the player's inventory, "
         + "so it cannot be opened from the console. Run 'player spawn' first, or run this from "
         + "in-game chat.");
      return null;
   }


   private boolean give(Level level, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "give") == null) {
         return false;
      }

      String itemID = args.get(1);
      int amount = Integer.parseInt(args.get(2));
      boolean added = serverClient.playerMob.getInv().addItem(new InventoryItem(itemID, amount), true, "harness");
      return this.check(logs, added, "gave " + amount + " " + itemID, "inventory would not take them");
   }

   private boolean close(ServerClient serverClient, CommandLog logs) {
      if (this.requirePlayer(serverClient, logs, "close") == null) {
         return false;
      }

      serverClient.closeContainer(true);
      logs.add("closed container");
      return true;
   }

   /** {@code click <slotIndex> <LEFT_CLICK|QUICK_MOVE|...>} — a raw container action. */
   private boolean click(ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      Container container = this.requireContainer(serverClient, logs, "click");
      if (container == null) {
         return false;
      }

      int slot = Integer.parseInt(args.get(1));
      ContainerAction action;
      try {
         action = ContainerAction.valueOf(args.get(2).toUpperCase());
      } catch (IllegalArgumentException e) {
         logs.add("FAIL unknown action '" + args.get(2) + "'; try LEFT_CLICK, RIGHT_CLICK, QUICK_MOVE or TAKE_ONE");
         return false;
      }

      ContainerActionResult result = container.applyContainerAction(slot, action);
      logs.add("click " + action + " on slot " + slot + " moved " + result.value);
      return true;
   }

   /**
    * {@code clear <radius> [tileStringID]} — strips objects, and optionally flattens tiles,
    * in a square around the world spawn.
    *
    * <p>Makes a scenario independent of what world generation happened to put there. Vanilla
    * ships {@code cleararea}, which does the same and more thoroughly — it clears every
    * object layer — but it targets a {@code ServerClient}, so it cannot run with nobody
    * connected. This clears the main object layer only, which is where placeable furniture
    * lives; decorative layers are left alone.
    *
    * <p>Run it <b>before</b> placing anything, or it will remove what was just placed.
    */
   private boolean clear(Level level, Point spawn, ArrayList<String> args, CommandLog logs) {
      int radius = Integer.parseInt(args.get(1));
      if (radius < 1 || radius > 200) {
         logs.add("FAIL radius must be between 1 and 200");
         return false;
      }

      int tileID = -1;
      if (args.size() > 2) {
         tileID = TileRegistry.getTileID(args.get(2));
         if (tileID < 0) {
            logs.add("FAIL unknown tile '" + args.get(2) + "'");
            return false;
         }
      }

      int objectsCleared = 0;

      for (int y = spawn.y - radius; y <= spawn.y + radius; y++) {
         for (int x = spawn.x - radius; x <= spawn.x + radius; x++) {
            if (level.getObjectID(x, y) != 0) {
               level.setObject(x, y, 0);
               objectsCleared++;
            }

            if (tileID >= 0) {
               level.setTile(x, y, tileID);
            }
         }
      }

      logs.add("cleared " + objectsCleared + " objects within " + radius + " tiles of spawn"
         + (tileID >= 0 ? ", tiles set to " + args.get(2) : ""));
      return true;
   }

   /**
    * {@code run <name>} — executes {@code <name>.txt} from the scenario directory, line by
    * line, as the caller.
    *
    * <p>This is what makes a session test a data file rather than Java. Lines are whole
    * console commands handed to {@code CommandsManager.runServerCommand}, so a session
    * scenario has exactly the same format as one the headless runner drives, can mix in
    * vanilla commands, and any line can be pasted into chat on its own to investigate a
    * failure. Composition belongs in the files; this class only supplies primitives.
    *
    * <p>The directory comes from {@code -Dnecesseheadlessharness.scenarios} and a name cannot escape
    * it, so this does not become a way to read arbitrary files off the host.
    */
   private boolean runScenario(Server server, ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      if (this.running) {
         logs.add("FAIL 'run' cannot nest");
         return false;
      }

      String root = System.getProperty("necesseheadlessharness.scenarios");
      if (root == null) {
         logs.add("FAIL scenario directory unknown: launch with -Dnecesseheadlessharness.scenarios=<dir> "
            + "(make run PACKETLOG=1 and the harness scripts set it already)");
         return false;
      }

      Path rootPath = Paths.get(root).toAbsolutePath().normalize();
      Path file = rootPath.resolve(args.get(1) + ".txt").normalize();
      if (!file.startsWith(rootPath)) {
         logs.add("FAIL scenario name must stay inside the scenario directory");
         return false;
      }

      List<String> lines;
      try {
         lines = Files.readAllLines(file);
      } catch (IOException e) {
         logs.add("FAIL could not read " + file + ": " + e.getMessage());
         return false;
      }

      this.running = true;
      try {
         for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
               continue;
            }

            logs.add("> " + line);
            server.commandsManager.runServerCommand(new ParsedCommand(line), serverClient);
         }
      } finally {
         this.running = false;
      }

      logs.add("ran scenario " + args.get(1));
      return true;
   }

   /**
    * Quick-stacks into the network: tops up what the network already holds and moves nothing
    * else. Asserting the difference from depositall is the point of testing it separately.
    */
   private boolean quickStack(ServerClient serverClient, CommandLog logs) {
      Container container = this.requireContainer(serverClient, logs, "quickstack");
      if (container == null) {
         return false;
      }

      container.applyContainerAction(Container.QUICK_STACK_SLOT, ContainerAction.LEFT_CLICK);
      logs.add("PASS quickstack applied");
      return true;
   }

   /** Restocks the player's stacks from the network. */
   private boolean restock(ServerClient serverClient, CommandLog logs) {
      Container container = this.requireContainer(serverClient, logs, "restock");
      if (container == null) {
         return false;
      }

      container.applyContainerAction(Container.RESTOCK_SLOT, ContainerAction.LEFT_CLICK);
      logs.add("PASS restock applied");
      return true;
   }

   /**
    * Where each subcommand's tile coordinates actually are, as an argument index for {@code dx}.
    *
    * <p>This used to scan every argument for integers and treat each consecutive pair as a
    * coordinate. That was wrong in a way that took a server crash to expose: coordinates always
    * arrive as a pair, but not every pair is a coordinate. {@code expect capacity 0 0 2560 2560}
    * produced the pair (2560, 2560) from the two slot counts and tried to load a region 2560
    * tiles away — which, never having been generated, sent the command down the region
    * *generation* path and deadlocked against the server tick.
    *
    * <p>So this is explicit. A subcommand absent from the map addresses no tile.
    */
   private static final Map<String, Integer> COORDINATE_ARG = new HashMap<>();

   static {
      COORDINATE_ARG.put("place", 2);
      COORDINATE_ARG.put("expect", 2);
      COORDINATE_ARG.put("fill", 1);
      COORDINATE_ARG.put("break", 1);
   }

   /**
    * The argument index where a subcommand's (dx, dy) pair starts, or -1 if it addresses no tile.
    * A registered verb answers for itself rather than being assumed to address nothing.
    */
   private int coordinateIndex(String sub) {
      Integer builtIn = COORDINATE_ARG.get(sub);
      if (builtIn != null) {
         return builtIn;
      }

      TestVerb verb = Harness.verb(sub);
      return verb == null ? -1 : verb.coordinateArgIndex();
   }

   /**
    * Forces the region a subcommand addresses to load, because reads do not load regions: the
    * object layer resolves a tile through {@code RegionBoundsExecutor} with
    * {@code loadIfNotLoaded = false}, so an unloaded region reads as *empty* rather than as
    * itself. Only a player normally triggers loading, and the harness has no player.
    *
    * <p>A freshly generated world hides this completely, since generation leaves every region in
    * memory. It appears only after a restart, where a scenario would see an empty world and
    * report a persistence bug that does not exist.
    */
   private void ensureRegionLoaded(Level level, Point spawn, ArrayList<String> args) {
      String sub = args.get(0).toLowerCase();

      // clear takes a radius rather than a coordinate, and works outward from spawn.
      if ("clear".equals(sub)) {
         int radius = args.size() > 1 ? Integer.parseInt(args.get(1)) : 0;
         this.loadRegionsAround(level, spawn.x - radius, spawn.y - radius, spawn.x + radius, spawn.y + radius);
         return;
      }

      int index = this.coordinateIndex(sub);
      if (index < 0 || args.size() <= index + 1) {
         return;
      }

      int x;
      int y;
      try {
         x = spawn.x + Integer.parseInt(args.get(index));
         y = spawn.y + Integer.parseInt(args.get(index + 1));
      } catch (NumberFormatException notCoordinates) {
         // 'expect total <itemStringID> <n>' addresses no tile, and shares a verb with those
         // that do. Failing to parse is the signal, not an error.
         return;
      }

      this.loadRegionsAround(level, x, y, x, y);
   }

   /**
    * Loads every region overlapping a tile box, inclusive.
    *
    * <p>No locking here any more. This used to take the level's monitor to match the order the tick
    * uses, because generating a region takes that monitor while holding a region lock. Running on
    * the server thread makes the question moot: there is no second thread to invert against.
    */
   private void loadRegionsAround(Level level, int fromX, int fromY, int toX, int toY) {
      loadRegionsIn(level, fromX, fromY, toX, toY);
   }

   private static void loadRegionsIn(Level level, int fromX, int fromY, int toX, int toY) {
      int bits = RegionManager.REGION_SIZE_BITS;
      for (int regionY = fromY >> bits; regionY <= toY >> bits; regionY++) {
         for (int regionX = fromX >> bits; regionX <= toX >> bits; regionX++) {
            level.regionManager.getRegion(regionX, regionY, true);
         }
      }
   }

   /**
    * How much of an item the player is carrying.
    *
    * <p>Counts every slot the manager exposes, including inactive equipment sets and the
    * temporary and cloud slots, so nothing can hide from a conservation check by sitting
    * somewhere unusual.
    */
   /**
    * The inventory of whatever sits at a tile, or null if nothing there holds one.
    *
    * <p>Keyed on the {@code OEInventory} interface rather than a concrete class, so this covers
    * every vanilla chest, barrel and crate as well as anything a mod adds -- which is what makes
    * the harness useful against a mod whose source you do not have.
    */
   private Inventory inventoryAt(Level level, int x, int y) {
      ObjectEntity entity = level.entityManager.getObjectEntity(x, y);
      return entity instanceof OEInventory ? ((OEInventory) entity).getInventory() : null;
   }

   private int countHeld(ServerClient serverClient, String itemID) {
      return serverClient.playerMob.getInv()
         .streamInventorySlots(true, true, true, true)
         .map(InventorySlot::getItem)
         .filter(item -> item != null && item.item.getStringID().equals(itemID))
         .mapToInt(InventoryItem::getAmount)
         .sum();
   }
}
