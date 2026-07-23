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
    public static final String VERSION = "1.5";

    public static HeadsUpDisplay hud;

    private static short lastGroundActionId = -1;
    private static short lastItemActionId = -1;
    private static short lastGroundObjectActionId = -1;
    private static String lastItemBaseName = null;
    private static boolean debug = false;

    // Cached hover object (unlimited lifetime, same as original 1.4).
    // Once a real GroundItem / TilePicker is seen it sticks until a new
    // non-null hover arrives. This is what makes 96 (tree) vs 97 (log)
    // reliable even when the client briefly reports null.
    private static Object lastHoveredObject = null;

    // Prevent our own /repeataction from re-recording the action into the wrong memory
    // (this is what stops old action IDs from "sticking" when switching targets)
    private static boolean isRepeating = false;

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

    // ==================== SAFE HOVERED OBJECT (unlimited cache, original 1.4 behaviour) ====================
    private static Object getCurrentHoveredObject() {
        try {
            if (hud != null && hud.getWorld() != null) {
                Object fresh = hud.getWorld().getCurrentHoveredObject();

                if (fresh != null) {
                    // Fresh call succeeded → trust it and update memory
                    lastHoveredObject = fresh;
                    return fresh;
                }
            }

            // Fresh call returned null (or failed) → stick with what we had before
            return lastHoveredObject;

        } catch (Exception e) {
            // On any error, fall back to last known hover
            return lastHoveredObject;
        }
    }

    private static boolean isHoveringGround() {
        try {
            Object hovered = getCurrentHoveredObject();
            if (hovered == null) return true;

            String name = hovered.getClass().getSimpleName().toLowerCase();

            // Treat every kind of picker as ground (surface, cave walls, ceiling, etc.)
            if (name.contains("picker")) {
                return true;
            }

            return name.contains("ground") || name.contains("tile");
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isHoveringGroundObject() {
        try {
            Object hovered = getCurrentHoveredObject();
            if (hovered == null) return false;

            Class<?> clazz = hovered.getClass();
            while (clazz != null) {
                if (clazz.getSimpleName().toLowerCase().contains("grounditem")) {
                    return true;
                }
                clazz = clazz.getSuperclass();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== INVENTORY SEARCH ====================
    // IMPORTANT: Player inventory root has two children: body (equipped items + wounds)
    // and inventory (actual backpack). We MUST skip body completely to avoid selecting
    // equipped rings, armor, wounds, etc. when re-selecting tools after an action.
    private static InventoryMetaItem findItemInInventory(String searchTerm) {
        if (hud == null || hud.getWorld() == null || searchTerm == null || searchTerm.isEmpty()) return null;

        try {
            InventoryMetaItem root = hud.getWorld().getInventoryManager().getPlayerInventory().getRootItem();

            // Locate the real inventory child (skip body)
            InventoryMetaItem inventoryRoot = null;

            // Preferred: find by name
            for (InventoryMetaItem child : root.getChildren()) {
                String base = "";
                String display = "";
                try {
                    if (child.getBaseName() != null) base = child.getBaseName().toLowerCase().trim();
                    if (child.getDisplayName() != null) display = child.getDisplayName().toLowerCase().trim();
                } catch (Exception ignored) {}

                if (base.equals("inventory") || display.equals("inventory") ||
                        base.contains("inventory") || display.contains("inventory")) {
                    inventoryRoot = child;
                    break;
                }
            }

            // Fallback 1: user confirmed structure is body first, inventory second
            if (inventoryRoot == null && root.getChildren() != null) {
                int idx = 0;
                for (InventoryMetaItem child : root.getChildren()) {
                    if (idx == 1) {
                        inventoryRoot = child;
                        break;
                    }
                    idx++;
                }
            }

            // Fallback 2: any child that is not body
            if (inventoryRoot == null) {
                for (InventoryMetaItem child : root.getChildren()) {
                    String base = "";
                    try {
                        if (child.getBaseName() != null) base = child.getBaseName().toLowerCase().trim();
                    } catch (Exception ignored) {}
                    if (!base.equals("body") && !base.contains("body")) {
                        inventoryRoot = child;
                        break;
                    }
                }
            }

            // Ultimate fallback (should never happen)
            if (inventoryRoot == null) {
                inventoryRoot = root;
            }

            return searchInventory(inventoryRoot, searchTerm.toLowerCase().trim());
        } catch (Exception e) {
            if (debug) debugLog("Search error: " + e.getMessage());
            return null;
        }
    }

    private static InventoryMetaItem searchInventory(InventoryMetaItem item, String term) {
        try {
            String base = item.getBaseName() != null ? item.getBaseName().toLowerCase().trim() : "";
            String display = item.getDisplayName() != null ? item.getDisplayName().toLowerCase().trim() : "";

            // Never match empty / null names (prevents bogus hits on internal nodes)
            if (!base.isEmpty() || !display.isEmpty()) {
                // Extract core type name (everything before first comma) for smarter same-type matching.
                // e.g. "butchering knife, iron" → "butchering knife"
                //      "string of cloth, cotton" → "string of cloth"
                //      "pickaxe, iron (Holy Pick!)" → "pickaxe"
                String coreType = term;
                int comma = term.indexOf(',');
                if (comma > 0) {
                    coreType = term.substring(0, comma).trim();
                }

                // Match if:
                // - full names match / contain each other, OR
                // - the item's name contains the core type (most useful for finding next identical tool)
                boolean nameContainsTerm = display.contains(term) || base.contains(term);
                boolean termContainsName = (base.length() > 2 && term.contains(base)) ||
                                           (display.length() > 2 && term.contains(display));
                boolean coreTypeMatch = (coreType.length() > 2) &&
                        (display.contains(coreType) || base.contains(coreType));

                if (nameContainsTerm || termContainsName || coreTypeMatch) {
                    return item;
                }
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
                                        // Tool changed → drop the old hover cache so a previous
                                        // GroundItem (e.g. a log) cannot mis-classify the next
                                        // tile action (e.g. plant) as GroundObject.
                                        lastHoveredObject = null;

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

                                    try {
                                        java.lang.reflect.Method m = HeadsUpDisplay.class.getMethod("setActiveToolItem", InventoryMetaItem.class);
                                        m.setAccessible(true);
                                        m.invoke(hud, nextItem);
                                        success = true;
                                    } catch (Exception ignored) {}

                                    if (!success) {
                                        try {
                                            java.lang.reflect.Method m = hud.getClass().getMethod("setActiveToolItem", InventoryMetaItem.class);
                                            m.setAccessible(true);
                                            m.invoke(hud, nextItem);
                                            success = true;
                                        } catch (Exception ignored) {}
                                    }

                                    if (!success) {
                                        try {
                                            java.lang.reflect.Method m = HeadsUpDisplay.class.getDeclaredMethod("setActiveToolItem", InventoryMetaItem.class);
                                            m.setAccessible(true);
                                            m.invoke(hud, nextItem);
                                            success = true;
                                        } catch (Exception ignored) {}
                                    }
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

            boolean hoveringGroundObject = isHoveringGroundObject();
            boolean hoveringGround       = isHoveringGround();           // includes all pickers

            if (hoveringGroundObject && lastGroundObjectActionId > 0) {
                actionToRepeat = lastGroundObjectActionId;
                memoryUsed = "Ground Object";

            } else if (hoveringGround && lastGroundActionId > 0) {
                actionToRepeat = lastGroundActionId;
                memoryUsed = "Ground";

            } else if (lastItemActionId > 0) {
                actionToRepeat = lastItemActionId;
                memoryUsed = "Item";

            } else {
                // Final fallback
                if (lastGroundActionId > 0) {
                    actionToRepeat = lastGroundActionId;
                    memoryUsed = "Ground (fallback)";
                } else if (lastGroundObjectActionId > 0) {
                    actionToRepeat = lastGroundObjectActionId;
                    memoryUsed = "Ground Object (fallback)";
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

                // Guard so the send does not re-record this action into the wrong memory
                isRepeating = true;
                try {
                    hud.getWorld().sendHoveredAction(action);
                } finally {
                    isRepeating = false;
                }

                String msg = "Repeated action ID: " + actionToRepeat + " (" + memoryUsed + ")";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);

            } catch (Exception e) {
                isRepeating = false; // safety
                String msg = "Failed to repeat action.";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
            }
            return true;
        }

        if (cleanCmd.equalsIgnoreCase("repeataction_debug")) {
            debug = !debug;
            String msg = debug
                    ? "Debug mode ENABLED (actions will be logged)"
                    : "Debug mode DISABLED";
            log(msg);
            if (hud != null) hud.consoleOutput(">>> " + msg);
            return true;
        }

        return false;
    }

    public static void recordAction(PlayerAction action) {
        try {
            // Do not let our own /repeataction overwrites pollute the three memories
            // with the wrong category. This was the main cause of "old action sticks".
            if (isRepeating) return;

            short actionId = action.getId();
            if (actionId <= 0 || ignoredActions.contains(actionId)) return;

            boolean onGround = isHoveringGround();
            boolean onGroundObject = isHoveringGroundObject();

            // Actions that should always be treated as item/tool actions
            boolean isItemAction = (actionId == 162); // Repair (add more here if needed, e.g. improve)

            if (debug) {
                Object hovered = getCurrentHoveredObject();
                String hoveredClass = (hovered != null) ? hovered.getClass().getSimpleName() : "null";
                debugLog("ACTION RECORDED → ID: " + actionId +
                        " | Ground: " + onGround +
                        " | GroundObject: " + onGroundObject +
                        " | HoveredClass: " + hoveredClass +
                        (isItemAction ? " | Forced Item Action" : ""));
            }

            if (onGroundObject && !isItemAction) {
                lastGroundObjectActionId = actionId;
            } else if (onGround && !isItemAction) {
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
            log("WARNING: Could not load properties file");
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
