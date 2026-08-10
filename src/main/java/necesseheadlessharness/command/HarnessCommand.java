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
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.Map;

import java.util.Arrays;

import necesse.engine.commands.AutoComplete;
import necesse.engine.commands.ChatCommand;
import necesseheadlessharness.HeadlessPlayer;
import necesseheadlessharness.Harness;
import necesseheadlessharness.ServerThreadTasks;
import necesse.engine.commands.CommandLog;
import necesse.engine.commands.ParsedCommand;
import necesse.engine.localization.message.GameMessage;
import necesseheadlessharness.Json;
import necesseheadlessharness.RpcSink;
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
import necesse.inventory.item.ItemCategory;
import necesse.inventory.recipe.Recipe;
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
      // Derived, never hand-written. This string previously advertised five verbs belonging to the
      // mod it was extracted from and omitted one it actually had, because it was prose maintained
      // by hand. BUILT_IN_VERBS is now the single list; a consumer's verbs come from the registry.
      StringBuilder usage = new StringBuilder("<");
      usage.append(String.join("|", BUILT_IN_VERBS));
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

      // The driver's channel, handled before anything touches the world.
      //
      // 'rpc' is a decorator rather than a parallel implementation: it strips the id, calls this
      // same method with the remaining arguments, and reports what happened. So marshalling onto
      // the server thread, region loading, dispatch and the verb registry are all reused, and no
      // verb knows whether it was called by a scenario or by a driver.
      String requested = args.get(0).toLowerCase();
      if (requested.equals("rpc")) {
         return this.rpc(client, server, serverClient, args, logs);
      }

      if (requested.equals("hello")) {
         return this.hello(logs);
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
            case "query":
               return this.query(level, spawn, server, serverClient, args, logs);
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
            case "craft":
               return this.craft(serverClient, args, logs);
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
         int actual = this.totalOf(level, itemID);

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

   /**
    * Every inventory on the level, not a chosen set: this exists to catch an action that creates or
    * destroys items, and a scan narrower than the whole level would miss items that moved somewhere
    * unexpected -- which is the interesting failure.
    *
    * <p>Shared by {@code expect total} and {@code query total} on purpose. A driver computing this
    * for itself would be a second definition of the same idea, and the two would drift.
    */
   private int totalOf(Level level, String itemID) {
      int total = 0;
      for (ObjectEntity entity : level.entityManager.objectEntities) {
         if (entity instanceof OEInventory && !entity.removed()) {
            total += this.countIn(((OEInventory)entity).getInventory(), itemID);
         }
      }

      return total;
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
      if (action.equals("clear")) {
         ServerClient client = HeadlessPlayer.current();
         if (client == null) {
            logs.add("FAIL no player to clear");
            return false;
         }

         // Clears exactly what 'query held' counts -- drag, main, equipment, cloud, trash and any
         // temporary inventory -- so the two cannot disagree about what the player is holding.
         //
         // This exists because respawning is not a reset. The headless player keeps a stable
         // authentication ID so the server reuses its player file, which is what makes it survive a
         // level change; the same mechanism restores its inventory on despawn/spawn. A suite found
         // that out the hard way, with a crafted item bleeding into the next two tests.
         int cleared = 0;
         for (InventorySlot slot : client.playerMob.getInv()
               .streamInventorySlots(true, true, true, true).collect(Collectors.toList())) {
            if (slot.getItem() != null) {
               slot.clearSlot();
               cleared++;
            }
         }

         logs.add("player cleared " + cleared + " slot(s)");
         return true;
      }
      logs.add("FAIL usage: player <spawn|despawn|clear>");
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
   /**
    * {@code craft <resultItemStringID> [amount]} -- crafts through the open container.
    *
    * <p>Generic rather than storage-specific because crafting is a {@code Container} capability and
    * not a crafting station's: {@code applyCraftingAction} is defined on {@code Container}, consumes
    * from {@code getCraftInventories()}, and every container registers the recipes that need no
    * station. So this drives a chest, a workstation or a storage terminal identically, and what
    * differs between them is only which inventories they contribute.
    *
    * <p>Recipes are addressed by the string ID of what they produce, because that is what a test
    * knows. The numeric id is an index into the container's own list, which is meaningless outside
    * one open container and would make a scenario unreadable.
    */
   private boolean craft(ServerClient serverClient, ArrayList<String> args, CommandLog logs) {
      Container container = this.requireContainer(serverClient, logs, "craft");
      if (container == null) {
         return false;
      }

      String wanted = args.get(1);
      int amount = args.size() > 2 ? Integer.parseInt(args.get(2)) : 1;

      // Walked by index rather than through streamRecipes(Tech...), which would mean enumerating
      // every tech to ask "any of them". getRecipe returns null past the end, and the index is
      // exactly what applyCraftingAction wants.
      int recipeID = -1;
      Recipe recipe = null;
      for (int id = 0; (recipe = container.getRecipe(id)) != null; id++) {
         if (recipe.resultItem.item.getStringID().equals(wanted)) {
            recipeID = id;
            break;
         }
      }

      if (recipeID < 0) {
         logs.add("FAIL this container offers no recipe producing '" + wanted + "'");
         return false;
      }

      int crafted = container.applyCraftingAction(recipeID, recipe.getRecipeHash(), amount, true);
      if (crafted <= 0) {
         // Deliberately does not blame the ingredients. A container may refuse for its own reasons --
         // a storage terminal refuses recipes whose crafting station is not installed -- and a
         // message that names one cause sends the reader looking in the wrong place.
         logs.add("FAIL the container refused to craft " + wanted + "; either the ingredients are "
               + "missing from every inventory it crafts from, or it does not allow that recipe");
         return false;
      }

      logs.add("craft " + wanted + " x" + crafted);
      return true;
   }

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
    * The harness's own verbs, in one place.
    *
    * <p>They are still a {@code switch} rather than registered {@link TestVerb}s, which is a real
    * shortcoming: a consumer's verb can declare its coordinate argument and its preconditions,
    * while a built-in cannot, and nothing can enumerate the built-ins except this list. Listing
    * them is the cheap part of fixing that, and it is what {@code hello} and the usage string are
    * derived from.
    */
   private static final List<String> BUILT_IN_VERBS = Arrays.asList(
      "place", "fill", "clear", "break", "give", "open", "close", "click", "craft", "quickstack",
      "restock", "expect", "query", "player", "run", "echo", "hello", "rpc");

   /** Kinds {@code expect} and {@code query} both understand without a consumer mod. */
   // 'category' and 'categories' answer about the item registry rather than about the level, so they
   // are queries with no expect counterpart -- there is nothing to assert, only something to read.
   private static final List<String> BUILT_IN_KINDS = Arrays.asList("item", "total", "held", "category", "categories");

   /**
    * Structured fields for the reply currently being assembled, or null when a verb was invoked by
    * a scenario or by hand rather than through {@code rpc}.
    *
    * <p>Static, and safe for a reason worth stating rather than assuming: console commands arrive
    * one at a time on a single scanner thread, and {@code rpc} calls the inner verb synchronously
    * before returning. There is never a second reply being built. If the harness ever grows a
    * second command source, this becomes wrong and must become a parameter.
    */
   private static Json.Writer replyData;

   /**
    * {@code rpc <id> <verb> ...} -- run a verb and report the outcome as one JSON line.
    *
    * <p>The id is the entire point. Before this, the harness was a one-way pipe: a driver wrote a
    * command to stdin and pattern-matched a game log for something that looked like a reply, with
    * no way to tell which reply belonged to which command, no values, and no way to distinguish a
    * failed assertion from a crash. An echoed id makes the channel a request/response one, which is
    * the difference between a scenario runner and something a test framework can drive.
    */
   private boolean rpc(Client client, Server server, ServerClient serverClient,
                       ArrayList<String> args, CommandLog logs) {
      if (args.size() < 3) {
         logs.add("FAIL usage: rpc <id> <verb> ...");
         return false;
      }

      String id = args.get(1);
      ArrayList<String> inner = new ArrayList<>(args.subList(2, args.size()));
      RecordingLog recording = new RecordingLog(logs);
      Json.Writer data = new Json.Writer();

      // Restored rather than cleared, so 'rpc 1 run scenario.txt' -- which re-enters this method
      // per line -- does not lose the outer reply's fields.
      Json.Writer enclosing = replyData;
      replyData = data;

      boolean ok;
      String error = null;
      try {
         ok = this.run(client, server, serverClient, inner, recording);
      } catch (Throwable t) {
         // A driver must be able to tell a failed assertion from a crash. Without this the JVM
         // stays alive, no reply is ever written, and the driver times out with no explanation.
         ok = false;
         error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
         recording.add("FAIL " + error);
      } finally {
         replyData = enclosing;
      }

      Json.Writer reply = new Json.Writer()
         .str("id", id)
         .bool("ok", ok && !recording.sawFailure())
         .str("verb", inner.get(0).toLowerCase())
         .strings("lines", recording.lines())
         .raw("checks", recording.checksJson());

      String fields = data.end();
      if (!fields.equals("{}")) {
         reply.raw("data", fields);
      }

      if (error != null) {
         reply.str("error", error);
      }

      RpcSink.emit(reply.end());
      return ok;
   }

   /**
    * {@code hello} -- the protocol version and the vocabulary actually present.
    *
    * <p>Two problems at once. A driver pinned to one protocol version needs to fail with a sentence
    * rather than behave oddly against a jar it does not match -- the jar and the Python client live
    * in one repo precisely so they share a version, but a stale installed jar is exactly the mistake
    * this project has already made once. And until now nothing could enumerate the real vocabulary,
    * so no linter or generated documentation was possible.
    */
   private boolean hello(CommandLog logs) {
      Json.Writer data = replyData != null ? replyData : new Json.Writer();
      data.num("protocol", Harness.PROTOCOL_VERSION)
         .strings("builtins", BUILT_IN_VERBS)
         .strings("verbs", Harness.verbNames())
         .strings("kinds", BUILT_IN_KINDS)
         .strings("expectations", Harness.expectationKinds())
         .strings("queries", this.queryableKinds());

      logs.add("harness protocol " + Harness.PROTOCOL_VERSION);
      if (replyData == null) {
         // Typed by hand rather than driven: still show the values, since a reply nobody can see is
         // worse than no reply.
         logs.add("DATA " + data.end());
      }

      return true;
   }

   /** Which kinds {@code query} can answer: the built-ins, plus any registered expectation that opts in. */
   private List<String> queryableKinds() {
      List<String> kinds = new ArrayList<>(BUILT_IN_KINDS);
      for (String kind : Harness.expectationKinds()) {
         TestVerb expectation = Harness.expectation(kind);
         if (expectation instanceof TestQuery && !kinds.contains(kind)) {
            kinds.add(kind);
         }
      }

      return kinds;
   }

   /**
    * {@code query <kind> [args...]} -- the value, not a verdict.
    *
    * <p>Deliberately shares argument positions with {@code expect} minus the expected values, so
    * {@code query item 4 0 ironbar} and {@code expect item 4 0 ironbar 40} address the same tile
    * with the same indices, and one COORDINATE_ARG entry covers both.
    *
    * <p>Values come from the same helpers {@code expect} uses. That is the point rather than tidiness:
    * a driver that counted items for itself would be a second definition of every assertion.
    */
   private boolean query(Level level, Point spawn, Server server, ServerClient serverClient,
                         ArrayList<String> args, CommandLog logs) {
      String kind = args.get(1).toLowerCase();
      Json.Writer data = replyData != null ? replyData : new Json.Writer();

      // A registered expectation wins over a built-in of the same name, exactly as with 'expect'.
      TestVerb registered = Harness.expectation(kind);
      if (registered instanceof TestQuery) {
         if (registered.needsPlayer() && this.requirePlayer(serverClient, logs, "query " + kind) == null) {
            return false;
         }

         ((TestQuery)registered).query(new TestContext(level, spawn, server, serverClient, args, logs), data);
      } else if ("total".equals(kind)) {
         data.str("item", args.get(2)).num("count", this.totalOf(level, args.get(2)));
      } else if ("held".equals(kind)) {
         if (this.requirePlayer(serverClient, logs, "query held") == null) {
            return false;
         }

         data.str("item", args.get(2)).num("count", this.countHeld(serverClient, args.get(2)));
      } else if ("category".equals(kind)) {
         Item item = ItemRegistry.getItem(args.get(2));
         if (item == null) {
            logs.add("FAIL no such item '" + args.get(2) + "'");
            return false;
         }

         // Innermost category first, so chain[last] is the top-level one. Reported as the chain
         // rather than as one name because a category filter is a question about ancestry: an item
         // is "a material" through its parents, never directly.
         List<String> chain = new ArrayList<>();
         for (ItemCategory category = ItemCategory.getItemsCategory(item); category != null
               && category.parent != null; category = category.parent) {
            chain.add(category.stringID);
         }

         data.str("item", args.get(2)).strings("chain", chain);
      } else if ("categories".equals(kind)) {
         // The game's top-level categories, as the running game has them rather than as a grep of
         // the source suggests. Any mod loaded alongside contributes to this too.
         List<String> top = new ArrayList<>();
         ItemCategory.masterCategory.getChildren().forEach(category -> top.add(category.stringID));
         data.strings("top", top);
      } else if ("item".equals(kind)) {
         int x = spawn.x + Integer.parseInt(args.get(2));
         int y = spawn.y + Integer.parseInt(args.get(3));
         Inventory inventory = this.inventoryAt(level, x, y);
         if (inventory == null) {
            logs.add("FAIL nothing with an inventory at " + args.get(2) + "," + args.get(3));
            return false;
         }

         data.str("item", args.get(4)).num("count", this.countIn(inventory, args.get(4)));
      } else {
         logs.add("FAIL query takes " + String.join(", ", this.queryableKinds()) + ", got '" + kind + "'");
         return false;
      }

      if (replyData == null) {
         logs.add("DATA " + data.end());
      }

      return true;
   }

   /**
    * Captures every line a verb logs, and passes it through to the real log unchanged.
    *
    * <p>Wrapping the log rather than changing the verbs is what makes {@code rpc} cost nothing per
    * verb: {@code CommandLog.add(String)} is public and non-final, so all fourteen built-ins and
    * every registered verb are recorded without knowing it. The lines are still printed, so a
    * driven run and a scenario run produce the same human-readable log.
    */
   private static final class RecordingLog extends CommandLog {

      private final CommandLog out;

      private final List<String> captured = new ArrayList<>();

      private RecordingLog(CommandLog out) {
         // Null client and server client: this instance never prints for itself, it only forwards.
         super(null, null);
         this.out = out;
      }

      @Override
      public void add(String message) {
         this.captured.add(message);
         this.out.add(message);
      }

      @Override
      public void add(GameMessage message) {
         this.captured.add(message.translate());
         this.out.add(message);
      }

      private List<String> lines() {
         return this.captured;
      }

      private boolean sawFailure() {
         for (String line : this.captured) {
            if (line.startsWith("FAIL")) {
               return true;
            }
         }

         return false;
      }

      /**
       * The PASS/FAIL lines as structured checks.
       *
       * <p>Parsing the harness's own output looks like the pattern-matching this layer exists to
       * remove, but it is a different thing: the format is authored here, in the same process, with
       * no log timestamps, no colour codes and no other mod's output interleaved. The alternative --
       * changing every verb to report checks structurally -- buys nothing a driver can use.
       */
      private String checksJson() {
         StringBuilder array = new StringBuilder("[");
         boolean first = true;
         for (String line : this.captured) {
            boolean pass = line.startsWith("PASS ");
            boolean fail = line.startsWith("FAIL ");
            if (!pass && !fail) {
               continue;
            }

            if (!first) {
               array.append(',');
            }

            array.append(new Json.Writer()
               .bool("ok", pass)
               .str("text", line.substring(5))
               .end());
            first = false;
         }

         return array.append(']').toString();
      }
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
      // query shares expect's argument positions, minus the expected values at the end.
      COORDINATE_ARG.put("query", 2);
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
