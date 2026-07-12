package net.roselyndsshadow.repeataction;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.shared.constants.PlayerAction;
import javassist.CtClass;
import javassist.CtMethod;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RepeatAction implements WurmClientMod, Initable, PreInitable {

    private static final Logger logger = Logger.getLogger(RepeatAction.class.getName());
    public static final String VERSION = "1.2";

    public static HeadsUpDisplay hud;

    private static short lastGroundActionId = -1;
    private static short lastItemActionId = -1;
    private static String lastItemBaseName = null;
    private static boolean debug = false;

    private static final Set<Short> ignoredActions = new HashSet<>();

    // ==================== LOGGING ====================
    private static void clearAndLog(String message) {
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", false);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception ignored) {}
    }

    private static void log(String message) {
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", true);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception ignored) {}
    }

    private static void debugLog(String message) {
        if (!debug) return;
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", true);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception ignored) {}
    }

    // ==================== INVENTORY SEARCH ====================
    private static InventoryMetaItem findItemInInventory(String searchTerm) {
        if (hud == null || hud.getWorld() == null || searchTerm == null || searchTerm.isEmpty()) return null;

        try {
            InventoryMetaItem root = hud.getWorld().getInventoryManager().getPlayerInventory().getRootItem();
            if (debug) debugLog("=== INVENTORY SEARCH START ===");
            if (debug) debugLog("Looking for: \"" + searchTerm + "\"");

            InventoryMetaItem result = searchInventory(root, searchTerm.toLowerCase());

            if (debug) {
                if (result != null) {
                    debugLog("MATCH FOUND → Base: \"" + result.getBaseName() + "\" | Display: \"" + result.getDisplayName() + "\"");
                } else {
                    debugLog("NO MATCH FOUND");
                }
                debugLog("=== INVENTORY SEARCH END ===");
            }
            return result;
        } catch (Exception e) {
            if (debug) debugLog("Search error: " + e.getMessage());
            return null;
        }
    }

    private static InventoryMetaItem searchInventory(InventoryMetaItem item, String term) {
        try {
            String base = item.getBaseName() != null ? item.getBaseName().toLowerCase() : "";
            String display = item.getDisplayName() != null ? item.getDisplayName().toLowerCase() : "";

            if (display.contains(term) || base.contains(term) ||
                    term.contains(display) || term.contains(base)) {
                return item;
            }
        } catch (Exception ignored) {}

        for (InventoryMetaItem child : item.getChildren()) {
            InventoryMetaItem found = searchInventory(child, term);
            if (found != null) return found;
        }
        return null;
    }

    // ==================== HOOKS ====================
    private static void hookActiveTool() {
        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                    "setActiveToolItem",
                    "(Lcom/wurmonline/client/game/inventory/InventoryMetaItem;)V",
                    () -> (proxy, method, args) -> {
                        try {
                            if (args.length > 0 && args[0] instanceof InventoryMetaItem) {
                                InventoryMetaItem item = (InventoryMetaItem) args[0];
                                if (item != null) {
                                    String name = null;
                                    try { name = item.getDisplayName(); } catch (Exception ignored) {}
                                    if (name == null || name.isEmpty()) {
                                        try { name = item.getBaseName(); } catch (Exception ignored) {}
                                    }
                                    if (name != null && !name.isEmpty()) {
                                        lastItemBaseName = name;
                                        debugLog("ITEM ACTIVATED → \"" + name + "\"");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            debugLog("setActiveToolItem hook error: " + e.getMessage());
                        }
                        return method.invoke(proxy, args);
                    }
            );
            log(">>> Hooked setActiveToolItem successfully");
        } catch (Exception e) {
            log(">>> FAILED to hook setActiveToolItem");
        }
    }

    private static void hookRemoveActiveToolItem() {
        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                    "removeActiveToolItem",
                    "(Lcom/wurmonline/client/game/inventory/InventoryMetaItem;)V",
                    () -> (proxy, method, args) -> {
                        try {
                            if (args.length > 0 && args[0] instanceof InventoryMetaItem && lastItemBaseName != null) {
                                InventoryMetaItem removed = (InventoryMetaItem) args[0];
                                debugLog("ACTIVE ITEM REMOVED → \"" + removed.getBaseName() +
                                        "\" | Looking for: \"" + lastItemBaseName + "\"");

                                InventoryMetaItem nextItem = findItemInInventory(lastItemBaseName);

                                if (nextItem != null) {
                                    boolean success = false;

                                    // Try 1
                                    try {
                                        java.lang.reflect.Method m = HeadsUpDisplay.class.getMethod("setActiveToolItem", InventoryMetaItem.class);
                                        m.setAccessible(true);
                                        m.invoke(hud, nextItem);
                                        success = true;
                                        debugLog("AUTO-ACTIVATED (try 1)");
                                    } catch (Exception e) {
                                        debugLog("Try 1 failed: " + e.getMessage());
                                    }

                                    // Try 2
                                    if (!success) {
                                        try {
                                            java.lang.reflect.Method m = hud.getClass().getMethod("setActiveToolItem", InventoryMetaItem.class);
                                            m.setAccessible(true);
                                            m.invoke(hud, nextItem);
                                            success = true;
                                            debugLog("AUTO-ACTIVATED (try 2)");
                                        } catch (Exception e) {
                                            debugLog("Try 2 failed: " + e.getMessage());
                                        }
                                    }

                                    // Try 3
                                    if (!success) {
                                        try {
                                            java.lang.reflect.Method m = HeadsUpDisplay.class.getDeclaredMethod("setActiveToolItem", InventoryMetaItem.class);
                                            m.setAccessible(true);
                                            m.invoke(hud, nextItem);
                                            success = true;
                                            debugLog("AUTO-ACTIVATED (try 3)");
                                        } catch (Exception e) {
                                            debugLog("Try 3 failed: " + e.getMessage());
                                        }
                                    }

                                    if (!success) {
                                        debugLog("ALL ACTIVATION ATTEMPTS FAILED");
                                    }
                                } else {
                                    debugLog("No matching item found");
                                }
                            }
                        } catch (Exception e) {
                            debugLog("removeActiveToolItem error: " + e.getMessage());
                        }
                        return method.invoke(proxy, args);
                    }
            );
            log(">>> Hooked removeActiveToolItem successfully");
        } catch (Exception e) {
            log(">>> FAILED to hook removeActiveToolItem");
        }
    }

    // ==================== COMMANDS ====================
    public static boolean handleInput(final String cmd, final String[] data) {
        String cleanCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;

        if (cleanCmd.equalsIgnoreCase("repeataction")) {
            short actionToRepeat = -1;
            String memoryUsed = "";

            if (isHoveringGround()) {
                actionToRepeat = lastGroundActionId;
                memoryUsed = "Ground";
                if (actionToRepeat <= 0) {
                    actionToRepeat = lastItemActionId;
                    memoryUsed = "Item (fallback)";
                }
            } else {
                memoryUsed = "Item";

                if (lastItemBaseName != null) {
                    InventoryMetaItem matchingItem = findItemInInventory(lastItemBaseName);
                    if (matchingItem != null) {
                        try {
                            java.lang.reflect.Method activate =
                                    HeadsUpDisplay.class.getMethod("setActiveToolItem", InventoryMetaItem.class);
                            activate.setAccessible(true);
                            activate.invoke(hud, matchingItem);

                            PlayerAction pa = new PlayerAction(lastItemActionId, PlayerAction.ANYTHING, "", false);
                            hud.sendAction(pa, matchingItem.getId());

                            String msg = "Repeated using matching item from inventory";
                            log(msg);
                            if (hud != null) hud.consoleOutput(">>> " + msg);
                            return true;
                        } catch (Exception e) {
                            debugLog("Smart repeat activation failed: " + e.getMessage());
                        }
                    }
                }

                actionToRepeat = lastItemActionId;
                if (actionToRepeat <= 0) {
                    actionToRepeat = lastGroundActionId;
                    memoryUsed = "Ground (fallback)";
                }
            }

            if (actionToRepeat <= 0) {
                String msg = "No suitable action captured for current target yet.";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
                return true;
            }

            try {
                PlayerAction action = new PlayerAction(actionToRepeat, PlayerAction.ANYTHING, "", false);
                hud.getWorld().sendHoveredAction(action);
                String msg = "Repeated action ID: " + actionToRepeat + " (" + memoryUsed + ")";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
            } catch (Exception e) {
                String msg = "Failed to repeat action.";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
            }
            return true;
        }

        if (cleanCmd.equalsIgnoreCase("repeataction_debug")) {
            debug = !debug;
            String msg = debug ? "Debug mode ENABLED" : "Debug mode DISABLED";
            log(msg);
            if (hud != null) hud.consoleOutput(">>> " + msg);
            return true;
        }

        return false;
    }

    private static boolean isHoveringGround() {
        try {
            if (hud == null || hud.getWorld() == null) return true;
            Object hovered = hud.getWorld().getCurrentHoveredObject();
            if (hovered == null) return false;
            String name = hovered.getClass().getSimpleName().toLowerCase();
            return name.contains("tilepicker") || name.contains("ground");
        } catch (Exception e) {
            return false;
        }
    }

    public static void recordAction(PlayerAction action) {
        try {
            short actionId = action.getId();
            if (actionId <= 0 || ignoredActions.contains(actionId)) return;

            boolean onGround = isHoveringGround();
            debugLog("ACTION RECORDED → ID: " + actionId + " | Ground: " + onGround);

            if (onGround) {
                lastGroundActionId = actionId;
            } else {
                lastItemActionId = actionId;
            }
        } catch (Exception e) {
            debugLog("recordAction error: " + e.getMessage());
        }
    }

    private static void loadIgnoreList() {
        ignoredActions.clear();
        File configFile = new File("mods/repeataction.properties");
        if (!configFile.exists()) return;

        try (FileInputStream fis = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.load(fis);
            String ignoreList = props.getProperty("ignoreActions", "");
            for (String idStr : ignoreList.split(",")) {
                try {
                    ignoredActions.add(Short.parseShort(idStr.trim()));
                } catch (NumberFormatException ignored) {}
            }
            log(">>> Ignore list loaded (" + ignoredActions.size() + " actions)");
        } catch (Exception e) {
            log("WARNING: Could not load ignore list");
        }
    }

    @Override
    public void init() {
        clearAndLog(">>> RepeatAction v" + VERSION + " loaded");
        loadIgnoreList();
        hookActiveTool();
        hookRemoveActiveToolItem();

        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                    "init", "(II)V",
                    () -> (proxy, method, args) -> {
                        method.invoke(proxy, args);
                        hud = (HeadsUpDisplay) proxy;
                        return null;
                    }
            );

            CtClass ctHUD = HookManager.getInstance().getClassPool()
                    .getCtClass("com.wurmonline.client.renderer.gui.HeadsUpDisplay");
            for (CtMethod method : ctHUD.getDeclaredMethods()) {
                for (CtClass param : method.getParameterTypes()) {
                    if (param.getName().equals("com.wurmonline.shared.constants.PlayerAction")) {
                        HookManager.getInstance().registerHook(
                                "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                                method.getName(), method.getMethodInfo().getDescriptor(),
                                () -> (proxy, m, args) -> {
                                    for (Object arg : args) {
                                        if (arg instanceof PlayerAction) recordAction((PlayerAction) arg);
                                    }
                                    return m.invoke(proxy, args);
                                }
                        );
                        break;
                    }
                }
            }

            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.console.WurmConsole",
                    "handleDevInput", "(Ljava/lang/String;[Ljava/lang/String;)Z",
                    () -> (proxy, method, args) -> {
                        if (handleInput((String) args[0], (String[]) args[1])) return true;
                        return method.invoke(proxy, args);
                    }
            );

            log(">>> Initialization complete");
        } catch (Throwable e) {
            log("Init error: " + e.getMessage());
        }
    }

    @Override
    public void preInit() {}
}
