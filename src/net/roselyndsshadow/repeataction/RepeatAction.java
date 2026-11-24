package net.roselyndsshadow.repeataction;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.WurmEventHandler;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.PickableUnit;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.gui.InventoryContainerWindow;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.shared.constants.PlayerAction;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RepeatAction implements WurmClientMod, Initable, PreInitable {

    private static final Logger logger = Logger.getLogger(RepeatAction.class.getName());
    public static final String VERSION = "2.14";

    // Runtime compatibility contract for WurmHelper's RepeatActionBot.
    // Keep this API level unchanged across ordinary RepeatAction releases.
    // Increment only if a breaking change requires a different bot.
    public static final int BOT_BRIDGE_API_VERSION = 1;

    public static HeadsUpDisplay hud;

    // For activation diagnostics only. Depletion decisions use getSourceItem() identity.
    private static String lastItemBaseName = null;
    private static boolean debug = false;
    // A debug log is started at most once per Wurm client run. The first time
    // debug is enabled during this process, the old file is truncated; later
    // debug off/on toggles append to the same run log.
    private static boolean debugLogStartedThisRun = false;

    // Authoritative learned-action memory. Outgoing packet IDs determine the
    // true target, then its BROAD replay category selects one action memory.
    // Different inventory templates share one slot; mobile/static world objects
    // share another. Precise target signatures remain available to the bot.
    private static final Map<TargetKey, Short> learnedActions =
            Collections.synchronizedMap(new LinkedHashMap<TargetKey, Short>());
    // Source identity is learned alongside the action for the same target key.
    // RepeatAction may replace this source ONLY from the player's own inventory.
    // External storage retrieval belongs to RepeatActionBot.
    private static final Map<TargetKey, RawSourceType> learnedSources =
            Collections.synchronizedMap(new LinkedHashMap<TargetKey, RawSourceType>());
    private static volatile TargetSignature lastCapturedTarget = null;
    private static volatile short lastCapturedActionId = -1;

    // Console output must only mutate Wurm GUI state from the game thread.
    // Commands may be invoked by WurmHelper/bot worker threads, so enqueue
    // messages here and drain them from World.tick().
    private static final ConcurrentLinkedQueue<String> CONSOLE_QUEUE =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_CONSOLE_LINES_PER_TICK = 64;

    // Cached hover object (unlimited lifetime, same as original 1.4).
    // Once a real GroundItem / TilePicker is seen it sticks until a new
    // non-null hover arrives. This is what makes 96 (tree) vs 97 (log)
    // reliable even when the client briefly reports null.
    private static Object lastHoveredObject = null;

    // Prevent our own /repeataction from re-recording the action into the wrong memory
    // (this is what stops old action IDs from "sticking" when switching targets)
    private static boolean isRepeating = false;

    private static final Set<Short> ignoredActions = new HashSet<>();

    // Material matching here concerns ACTIVE SOURCE replacement, not the
    // target's broad action-memory category. Cedar/birch sprouts and wood
    // variants may substitute as sources, whereas ores/lumps stay material-
    // specific. Extra exact-source names are configurable in properties.
    private static final Set<String> materialSpecificItems = new HashSet<String>();

    // Extra safety used only by WurmHelper RepeatActionBot ("repeataction bot").
    // Manual "repeataction" remains unchanged.
    private static final short BOT_IGNORE_EAT = 182;
    private static short lastBotBlockedActionId = -1;


    // =====================================================================
    // AUTHORITATIVE OUTGOING ACTION CAPTURE
    // =====================================================================
    // RepeatAction itself learns from the ACTUAL packet-level source + target IDs
    // immediately before they are sent to the server.  The specific target ID is
    // resolved to a reusable TYPE and then discarded.  The public raw* fields are
    // retained as the compatibility bridge used by RepeatActionBot ground mode.
    private static volatile long rawCaptureTime = 0L;
    private static volatile short rawCaptureActionId = -1;
    private static volatile String rawTargetKind = "";
    private static volatile byte rawTargetTileTypeId = -1;
    private static volatile String rawTargetLabel = "";
    private static volatile String rawTargetModel = "";
    private static volatile String rawTargetName = "";
    private static volatile byte rawTargetMaterial = -1;
    private static volatile short rawTargetIconId = -1;
    private static volatile byte rawTargetPlaceable = -1;
    private static volatile String rawTargetClass = "";

    private static volatile short rawSourceType = -1;
    private static volatile String rawSourceBaseName = "";
    private static volatile byte rawSourceMaterial = -1;
    private static volatile byte rawSourceExactMaterial = -1;
    private static volatile String rawSourceDisplayName = "";
    private static volatile float rawSourceWeight = -1f;

    // -----------------------------------------------------------------
    // CROSS-MOD BRIDGE
    // -----------------------------------------------------------------
    // Wurm client mods are isolated by Ago's modloader classloaders.  A
    // WurmHelper bot therefore cannot safely Class.forName(RepeatAction).
    // HookManager callbacks live in the shared modloader layer and are the
    // supported neutral meeting point: the bot receives only a proxy Object
    // exposing primitives/Strings, never this mod's classes.
    private static final String BOT_BRIDGE_ANCHOR = "com.wurmonline.client.WurmEventHandler";
    private static final String BOT_BRIDGE_NAME = "roselynd_repeat_action_api";

    public static final class BotBridge {
        /** Read the version of the mod that is actually running, not .properties metadata. */
        public String getRuntimeVersion() { return RepeatAction.VERSION; }
        /** Stable compatibility level independent of the mod's release version. */
        public int getBotBridgeApiVersion() { return RepeatAction.BOT_BRIDGE_API_VERSION; }
        public int repeatCurrentTargetForBot() { return RepeatAction.repeatCurrentTargetForBot(); }
        public int repeatTargetForBot(long targetId) { return RepeatAction.repeatTargetForBot(targetId); }
        public int repeatCurrentTargetForBotGeneralSource() { return RepeatAction.repeatCurrentTargetForBotGeneralSource(); }
        public int repeatTargetForBotGeneralSource(long targetId) { return RepeatAction.repeatTargetForBotGeneralSource(targetId); }
        public short getCurrentTargetSourceType() { return RepeatAction.getCurrentTargetSourceType(); }
        public String getCurrentTargetSourceBaseName() { return RepeatAction.getCurrentTargetSourceBaseName(); }
        public byte getCurrentTargetSourceMaterial() { return RepeatAction.getCurrentTargetSourceMaterial(); }
        public byte getCurrentTargetSourceExactMaterial() { return RepeatAction.getCurrentTargetSourceExactMaterial(); }
        public String getCurrentTargetSourceDisplayName() { return RepeatAction.getCurrentTargetSourceDisplayName(); }
        public float getCurrentTargetSourceWeight() { return RepeatAction.getCurrentTargetSourceWeight(); }
        public short getTargetSourceType(long targetId) { return RepeatAction.getTargetSourceType(targetId); }
        public String getTargetSourceBaseName(long targetId) { return RepeatAction.getTargetSourceBaseName(targetId); }
        public byte getTargetSourceMaterial(long targetId) { return RepeatAction.getTargetSourceMaterial(targetId); }
        public byte getTargetSourceExactMaterial(long targetId) { return RepeatAction.getTargetSourceExactMaterial(targetId); }
        public String getTargetSourceDisplayName(long targetId) { return RepeatAction.getTargetSourceDisplayName(targetId); }
        public float getTargetSourceWeight(long targetId) { return RepeatAction.getTargetSourceWeight(targetId); }
        /** The same material-specific item policy used by RepeatAction itself. */
        public byte getItemMatchMaterialForBot(String baseName, byte actualMaterial) {
            return RepeatAction.isMaterialSpecificItem(baseName) ? actualMaterial : (byte)-1;
        }
        /** Explicit WurmHelper bot-session boundary: forget every learned replay. */
        public void clearBotSession() { RepeatAction.clearBotSession(); }
        public void clearGroundActionCapture() { RepeatAction.clearGroundActionCapture(); }
        public long getGroundActionCaptureTime() { return RepeatAction.getGroundActionCaptureTime(); }
        public short getGroundActionCaptureActionId() { return RepeatAction.getGroundActionCaptureActionId(); }
        public String getGroundActionCaptureTargetKind() { return RepeatAction.getGroundActionCaptureTargetKind(); }
        public byte getGroundActionCaptureTileTypeId() { return RepeatAction.getGroundActionCaptureTileTypeId(); }
        public String getGroundActionCaptureTargetLabel() { return RepeatAction.getGroundActionCaptureTargetLabel(); }
        public String getGroundActionCaptureTargetModel() { return RepeatAction.getGroundActionCaptureTargetModel(); }
        public String getGroundActionCaptureTargetName() { return RepeatAction.getGroundActionCaptureTargetName(); }
        public byte getGroundActionCaptureTargetMaterial() { return RepeatAction.getGroundActionCaptureTargetMaterial(); }
        public short getGroundActionCaptureTargetIconId() { return RepeatAction.getGroundActionCaptureTargetIconId(); }
        public byte getGroundActionCaptureTargetPlaceable() { return RepeatAction.getGroundActionCaptureTargetPlaceable(); }
        public String getGroundActionCaptureTargetClass() { return RepeatAction.getGroundActionCaptureTargetClass(); }
        public short getGroundActionCaptureSourceType() { return RepeatAction.getGroundActionCaptureSourceType(); }
        public String getGroundActionCaptureSourceBaseName() { return RepeatAction.getGroundActionCaptureSourceBaseName(); }
        public byte getGroundActionCaptureSourceMaterial() { return RepeatAction.getGroundActionCaptureSourceMaterial(); }
        public byte getGroundActionCaptureSourceExactMaterial() { return RepeatAction.getGroundActionCaptureSourceExactMaterial(); }
        public String getGroundActionCaptureSourceDisplayName() { return RepeatAction.getGroundActionCaptureSourceDisplayName(); }
        public float getGroundActionCaptureSourceWeight() { return RepeatAction.getGroundActionCaptureSourceWeight(); }
    }

    private static void registerBotBridge() {
        try {
            javassist.CtClass anchor = HookManager.getInstance().getClassPool().get(BOT_BRIDGE_ANCHOR);
            HookManager.getInstance().addCallback(anchor, BOT_BRIDGE_NAME, new BotBridge());
            logger.info("RepeatAction shared WurmHelper bridge registered");
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Could not register RepeatAction shared WurmHelper bridge", t);
        }
    }

    public static synchronized void clearGroundActionCapture() {
        rawCaptureTime = 0L;
        rawCaptureActionId = -1;
        rawTargetKind = "";
        rawTargetTileTypeId = -1;
        rawTargetLabel = "";
        rawTargetModel = "";
        rawTargetName = "";
        rawTargetMaterial = -1;
        rawTargetIconId = -1;
        rawTargetPlaceable = -1;
        rawTargetClass = "";
        rawSourceType = -1;
        rawSourceBaseName = "";
        rawSourceMaterial = -1;
        rawSourceExactMaterial = -1;
        rawSourceDisplayName = "";
        rawSourceWeight = -1f;
    }

    /**
     * A new bot run must NEVER inherit the previous run's action or source.
     * The learned mappings belong here, not to the WurmHelper bot instance,
     * so clearing only the bot's fields is insufficient.
     */
    public static synchronized void clearBotSession() {
        learnedActions.clear();
        learnedSources.clear();
        lastCapturedTarget = null;
        lastCapturedActionId = -1;
        lastBotBlockedActionId = -1;
        lastItemBaseName = null;
        lastHoveredObject = null;
        clearGroundActionCapture();
    }

    public static long getGroundActionCaptureTime() { return rawCaptureTime; }
    public static short getGroundActionCaptureActionId() { return rawCaptureActionId; }
    public static String getGroundActionCaptureTargetKind() { return rawTargetKind; }
    public static byte getGroundActionCaptureTileTypeId() { return rawTargetTileTypeId; }
    public static String getGroundActionCaptureTargetLabel() { return rawTargetLabel; }
    public static String getGroundActionCaptureTargetModel() { return rawTargetModel; }
    public static String getGroundActionCaptureTargetName() { return rawTargetName; }
    public static byte getGroundActionCaptureTargetMaterial() { return rawTargetMaterial; }
    public static short getGroundActionCaptureTargetIconId() { return rawTargetIconId; }
    public static byte getGroundActionCaptureTargetPlaceable() { return rawTargetPlaceable; }
    public static String getGroundActionCaptureTargetClass() { return rawTargetClass; }
    public static short getGroundActionCaptureSourceType() { return rawSourceType; }
    public static String getGroundActionCaptureSourceBaseName() { return rawSourceBaseName; }
    public static byte getGroundActionCaptureSourceMaterial() { return rawSourceMaterial; }
    public static byte getGroundActionCaptureSourceExactMaterial() { return rawSourceExactMaterial; }
    public static String getGroundActionCaptureSourceDisplayName() { return rawSourceDisplayName; }
    public static float getGroundActionCaptureSourceWeight() { return rawSourceWeight; }

    private static boolean ignoreRawCaptureAction(short id) {
        // Movement/vehicle bookkeeping must never replace a demonstrated work action.
        return id == 11 || id == 106 || id == 107 || id == 331 || id == 332 || id == 333;
    }

    private enum TargetKind {
        SURFACE_TILE,
        SURFACE_BORDER,
        SURFACE_CORNER,
        CAVE_FLOOR,
        CAVE_CEILING,
        CAVE_WALL,
        CAVE_BORDER,
        CAVE_CORNER,
        WORLD_GROUND_OBJECT,
        CREATURE,
        WORLD_OBJECT,
        INVENTORY_ITEM,
        MISC,
        // Internal exclusion marker.  Never learned or replayed.
        IGNORED_BULK
    }

    /**
     * Action memory is intentionally BROAD, like the original RepeatAction.
     * Precise TargetKind/TargetSignature information is still retained for
     * packet targeting and RepeatActionBot's ground-target discovery.
     */
    private enum ReplayCategory {
        INVENTORY_ITEM,
        GROUND_OBJECT,
        CREATURE,
        TILE,
        CAVE_WALL_OR_CEILING,
        SURFACE_BORDER,
        SURFACE_CORNER,
        CAVE_BORDER,
        CAVE_CORNER,
        MISC,
        IGNORED_BULK
    }

    private static ReplayCategory replayCategory(TargetKind kind) {
        switch (kind) {
            case INVENTORY_ITEM: return ReplayCategory.INVENTORY_ITEM;
            case WORLD_GROUND_OBJECT:
            case WORLD_OBJECT: return ReplayCategory.GROUND_OBJECT;
            case CREATURE: return ReplayCategory.CREATURE;
            case SURFACE_TILE:
            case CAVE_FLOOR: return ReplayCategory.TILE;
            case CAVE_WALL:
            case CAVE_CEILING: return ReplayCategory.CAVE_WALL_OR_CEILING;
            case SURFACE_BORDER: return ReplayCategory.SURFACE_BORDER;
            case SURFACE_CORNER: return ReplayCategory.SURFACE_CORNER;
            case CAVE_BORDER: return ReplayCategory.CAVE_BORDER;
            case CAVE_CORNER: return ReplayCategory.CAVE_CORNER;
            case IGNORED_BULK: return ReplayCategory.IGNORED_BULK;
            default: return ReplayCategory.MISC;
        }
    }

    private enum InventoryLocation {
        NONE,
        PLAYER_INVENTORY,
        VEHICLE_INVENTORY,
        ORDINARY_CONTAINER
    }

    /**
     * Memory equality uses the broad replay CATEGORY alone.  The original
     * target's concrete kind/template/model is still retained for diagnostics
     * and for RepeatActionBot, but does not restrict the next manual replay.
     */
    private static final class TargetKey {
        final TargetKind kind;
        final int numericType;
        final String stableType;
        final ReplayCategory category;

        TargetKey(TargetKind kind, int numericType, String stableType) {
            this.kind = kind;
            this.numericType = numericType;
            this.stableType = stableType == null ? "" : stableType.toLowerCase(Locale.ROOT).trim();
            this.category = replayCategory(kind);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TargetKey)) return false;
            TargetKey other = (TargetKey)o;
            return category == other.category;
        }

        @Override public int hashCode() {
            return category.hashCode();
        }

        @Override public String toString() {
            return category.toString();
        }
    }

    private static final class TargetSignature {
        final TargetKey key;
        final TargetKind kind;
        final InventoryLocation location;
        final byte tileTypeId;
        final short itemType;
        final String label;
        final String model;
        final String baseName;
        final byte material;
        final short iconId;
        final byte placeable;
        final String objectClass;

        TargetSignature(TargetKey key, TargetKind kind, InventoryLocation location,
                        byte tileTypeId, short itemType, String label, String model,
                        String baseName, byte material, short iconId, byte placeable,
                        String objectClass) {
            this.key = key;
            this.kind = kind;
            this.location = location;
            this.tileTypeId = tileTypeId;
            this.itemType = itemType;
            this.label = safe(label);
            this.model = safe(model);
            this.baseName = safe(baseName);
            this.material = material;
            this.iconId = iconId;
            this.placeable = placeable;
            this.objectClass = safe(objectClass);
        }

        boolean isGroundModeTarget() {
            // RepeatActionBot currently knows how to search static tiles/ground
            // objects, not Wurm's mobile creature/vehicle renderables.  Mobile
            // objects (ships/boats/carts represented in getCreatures()) remain
            // fully valid standalone RepeatAction targets without polluting the
            // bot's ground-demonstration bridge.
            return kind != TargetKind.INVENTORY_ITEM
                    && kind != TargetKind.CREATURE
                    && kind != TargetKind.WORLD_OBJECT
                    && kind != TargetKind.MISC
                    && kind != TargetKind.IGNORED_BULK;
        }

        String describe() {
            StringBuilder sb = new StringBuilder(kind.toString());
            if (kind == TargetKind.INVENTORY_ITEM) sb.append("/").append(location);
            String n = !baseName.isEmpty() ? baseName : (!label.isEmpty() ? label : model);
            if (!n.isEmpty()) sb.append(" ").append(n);
            return sb.toString();
        }
    }

    private static final class RawSourceType {
        final short type;
        final String baseName;
        final byte material;
        final byte exactMaterial;
        final String displayName;
        final float weight;
        RawSourceType(short type, String baseName, byte material, float weight, String displayName) {
            this.type = type;
            this.baseName = normalizeBaseItemName(baseName);
            // -1 means "any material".  Keep the concrete material only for ore,
            // lump, or an explicitly configured base item.
            this.material = isMaterialSpecificItem(this.baseName) ? material : (byte)-1;
            this.exactMaterial = material;
            this.displayName = safe(displayName).trim();
            this.weight = weight;
        }

        boolean matches(InventoryMetaItem item) {
            if (item == null) return false;
            try {
                if (type > 0 && item.getType() == type &&
                        (material < 0 || item.getMaterialId() == material)) return true;
                String base = normalizeBaseItemName(item.getBaseName());
                return !baseName.isEmpty() && baseName.equals(base) &&
                        (material < 0 || item.getMaterialId() == material);
            } catch (Throwable ignored) { return false; }
        }

        boolean matchesExact(InventoryMetaItem item) {
            if (item == null) return false;
            try {
                if (type > 0 && item.getType() == type &&
                        (exactMaterial < 0 || item.getMaterialId() == exactMaterial)) return true;
                String base = normalizeBaseItemName(item.getBaseName());
                return !baseName.isEmpty() && baseName.equals(base) &&
                        (exactMaterial < 0 || item.getMaterialId() == exactMaterial);
            } catch (Throwable ignored) { return false; }
        }

        boolean hasGeneralFallback() {
            return material < 0 && exactMaterial >= 0;
        }

        String sourceLabel() {
            return displayName.isEmpty() ? baseName : displayName;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Wurm's base name is the general commodity identity: "sprout", "log",
     * "ore", "lump", etc.  Strip quantity/material decoration defensively in
     * case a custom server exposes a display-like base name. */
    private static String normalizeBaseItemName(String value) {
        String n = safe(value).toLowerCase(Locale.ROOT).trim();
        n = n.replaceFirst("\\s*\\(\\d+x\\)\\s*$", "").trim();
        int comma = n.indexOf(',');
        if (comma > 0) n = n.substring(0, comma).trim();
        return n;
    }

    private static boolean isMaterialSpecificItem(String baseName) {
        String base = normalizeBaseItemName(baseName);
        return !base.isEmpty() && materialSpecificItems.contains(base);
    }

    private static TargetKey inventoryItemKey(short type, String baseName, byte material) {
        String base = normalizeBaseItemName(baseName);
        boolean specific = isMaterialSpecificItem(base);
        // Retain specific template/material metadata for diagnosis and bot
        // signatures, though TargetKey equality now uses category alone.
        String stable;
        if (type > 0) stable = specific ? "material:" + (material & 0xff) : "";
        else stable = base + (specific ? "|material:" + (material & 0xff) : "");
        return new TargetKey(TargetKind.INVENTORY_ITEM, type, stable);
    }

    private static synchronized void captureActualOutgoingAction(short actionId, long sourceId, long[] targets) {
        if (isRepeating || actionId <= 0 || ignoredActions.contains(actionId) || ignoreRawCaptureAction(actionId)) return;
        if (targets == null || targets.length == 0) return;

        TargetSignature target = resolveActualTargetSignature(targets[0]);
        if (target == null) {
            // A positive outgoing target id must never become "unsupported".
            // This final guard is deliberately redundant with the resolver's
            // MISC fallback so custom-server target types remain viable even if
            // a future Wurm/client change breaks one of the richer classifiers.
            target = miscSignature(targets[0], "capture fallback");
        }
        if (target != null && target.kind == TargetKind.IGNORED_BULK) {
            // Bulk rows and their contents are not actionable item targets for
            // RepeatAction.  Crucially, do not let them teach the MISC category.
            debugLog("ACTION IGNORED: bulk-container target #" + targets[0]);
            return;
        }
        if (target == null) {
            // Only genuinely unusable/non-target ids (for example <= 0) can
            // reach this point. They are not classifiable command targets.
            debugLog("ACTION CAPTURE could not resolve non-positive/invalid target: action=" +
                    actionId + " target=" + targets[0]);
            return;
        }

        // The actual packet target is authoritative.  This replaces the old
        // hover-guessing Ground / GroundObject / Item caches.
        learnedActions.put(target.key, actionId);
        lastCapturedTarget = target;
        lastCapturedActionId = actionId;

        RawSourceType source = resolveActualSourceType(sourceId);
        // The source memory belongs to the same last-learned action for this
        // target key. If the action has no source, clear any older source that
        // may have been associated with an earlier action on the same target.
        if (source != null) learnedSources.put(target.key, source);
        else learnedSources.remove(target.key);

        // Keep the public ground-capture API used by RepeatActionBot.  Inventory
        // actions are valid RepeatAction memories, but intentionally do NOT
        // satisfy RepeatActionBot's ground demonstration capture.
        if (target.isGroundModeTarget()) {
            rawCaptureActionId = actionId;
            rawCaptureTime = System.currentTimeMillis();
            rawTargetKind = target.kind == TargetKind.WORLD_GROUND_OBJECT ? "GROUND_OBJECT" : target.kind.toString();
            rawTargetTileTypeId = target.tileTypeId;
            rawTargetLabel = target.label;
            rawTargetModel = target.model;
            rawTargetName = target.baseName;
            rawTargetMaterial = target.material;
            rawTargetIconId = target.iconId;
            rawTargetPlaceable = target.placeable;
            rawTargetClass = target.objectClass;

            if (source != null) {
                rawSourceType = source.type;
                rawSourceBaseName = source.baseName;
                rawSourceMaterial = source.material;
                rawSourceExactMaterial = source.exactMaterial;
                rawSourceDisplayName = source.displayName;
                rawSourceWeight = source.weight;
            } else {
                rawSourceType = -1;
                rawSourceBaseName = "";
                rawSourceMaterial = -1;
                rawSourceExactMaterial = -1;
                rawSourceDisplayName = "";
                rawSourceWeight = -1f;
            }
        }

        debugLog("ACTION LEARNED id=" + actionId + " target=" + target.describe() +
                " key=" + target.key +
                (source == null ? " source=<none>" : " source=" + source.sourceLabel() +
                        " [type=" + source.type + ", exactMaterial=" + (source.exactMaterial & 0xff) +
                        (source.material < 0 ? ", general-material-match" : ", material-specific") + "]"));
    }

    /** Resolve an actual Wurm command-target id into a stable reusable type. */
    private static TargetSignature resolveActualTargetSignature(long targetId) {
        if (hud == null || hud.getWorld() == null || targetId <= 0L) return null;
        try {
            int counterType = (int)(targetId & 0xFFL);

            // Tile body: surface=3, cave=17. Cave side is encoded in bits 48..55.
            if (counterType == 3) {
                int x = Tiles.decodeTileX(targetId);
                int y = Tiles.decodeTileY(targetId);
                Tiles.Tile tile = hud.getWorld().getNearTerrainBuffer().getTileType(x, y);
                TargetSignature sig = tileSignature(TargetKind.SURFACE_TILE, tile, "surface tile");
                if (sig != null) return sig;
                // Unknown/unloaded/custom tile data falls through to MISC.
            }
            if (counterType == 17) {
                int x = Tiles.decodeTileX(targetId);
                int y = Tiles.decodeTileY(targetId);
                int side = (int)((targetId >>> 48) & 0xFFL);
                Tiles.Tile tile = hud.getWorld().getCaveBuffer().getTileType(x, y);
                TargetKind kind = side == 0 ? TargetKind.CAVE_FLOOR :
                        side == 1 ? TargetKind.CAVE_CEILING :
                        (side >= 2 && side <= 5 ? TargetKind.CAVE_WALL : null);
                if (kind != null) {
                    TargetSignature sig = tileSignature(kind, tile,
                            kind == TargetKind.CAVE_FLOOR ? "cave floor" :
                            kind == TargetKind.CAVE_CEILING ? "cave ceiling" : "cave wall");
                    if (sig != null) return sig;
                }
                // Unknown custom cave sides/tile data fall through to MISC.
            }

            // Surface/cave borders and corners are separate Wurm counter types.
            // Bit 15 (0x80 in the border-direction byte) is the underground flag.
            if (counterType == 12 || counterType == 27) {
                int x = Tiles.decodeTileX(targetId);
                int y = Tiles.decodeTileY(targetId);
                boolean cave = (((targetId >>> 8) & 0x80L) != 0L);
                Tiles.Tile tile = cave
                        ? hud.getWorld().getCaveBuffer().getTileType(x, y)
                        : hud.getWorld().getNearTerrainBuffer().getTileType(x, y);
                TargetKind kind;
                if (counterType == 12) kind = cave ? TargetKind.CAVE_BORDER : TargetKind.SURFACE_BORDER;
                else kind = cave ? TargetKind.CAVE_CORNER : TargetKind.SURFACE_CORNER;
                TargetSignature sig = tileSignature(kind, tile,
                        kind.toString().toLowerCase(Locale.ROOT).replace('_', ' '));
                if (sig != null) return sig;
                // Unknown/unloaded/custom border data falls through to MISC.
            }

            // Item/object ids use counter type 2 in several client collections.
            // Static dropped/placed items live in groundItems; ships/boats and
            // Creature-map entries cover TWO semantic classes in Wurm: actual
            // living creatures/NPCs and item-like mobile world objects such as
            // ships, boats, carts and wagons. CreatureCellRenderable.isItem() is
            // the authoritative discriminator; do not infer this from names.
            // Ordinary inventory rows live in inventory trees.
            if (counterType == 2) {
                TargetSignature ground = findGroundObjectSignature(targetId);
                if (ground != null) return ground;
                TargetSignature creatureMap = findCreatureMapSignature(targetId);
                if (creatureMap != null) return creatureMap;
                TargetSignature inventory = findInventoryTargetSignature(targetId);
                if (inventory != null) return inventory;
            }

            // TOTAL FALLBACK: positive Wurm targets outside the recognized
            // representations use the MISC action-memory category. The exact
            // ID is retained only to address the real target during replay.
            return miscSignature(targetId, "unclassified counter type " + counterType);
        } catch (Throwable t) {
            debugLog("target rich-classification failed id=" + targetId + ": " + t.getMessage());
            return miscSignature(targetId, "classifier exception");
        }
    }

    /**
     * Catch-all target signature.  RepeatAction must be future/custom-mod safe:
     * failure to recognize a target's semantic class is never a reason to drop
     * an action. All such targets share the broad MISC memory, while replay
     * still sends to the exact currently selected target ID.
     */
    private static TargetSignature miscSignature(long targetId, String reason) {
        if (targetId <= 0L) return null;
        int counterType = (int)(targetId & 0xFFL);
        String stable = "counter:" + counterType + "|id:" + Long.toUnsignedString(targetId);
        String label = "misc target (counter " + counterType + ")";
        debugLog("TARGET FALLBACK → MISC: target=" + targetId +
                " counterType=" + counterType + " reason=" + safe(reason));
        return new TargetSignature(
                new TargetKey(TargetKind.MISC, counterType, stable),
                TargetKind.MISC, InventoryLocation.NONE,
                (byte)-1, (short)-1, label, "", "", (byte)-1,
                (short)-1, (byte)-1, "");
    }

    private static TargetSignature tileSignature(TargetKind kind, Tiles.Tile tile, String suffix) {
        if (tile == null) return null;
        String tileName = safe(tile.getName()).toLowerCase(Locale.ROOT).trim();
        String label = tileName.isEmpty() ? suffix : tileName + " " + suffix;

        // Preserve fine tile metadata for the ground bot's demonstrations.
        // Replay memory itself now groups surface tiles with cave floors and
        // groups cave walls with ceilings, independent of the tile subtype.
        TargetKey key;
        if (kind == TargetKind.SURFACE_TILE && isWoodySurfaceTile(tile))
            key = new TargetKey(kind, -1, "woody_vegetation");
        else
            key = new TargetKey(kind, tile.getId(), "");

        return new TargetSignature(key, kind, InventoryLocation.NONE,
                tile.getId(), (short)-1, label, "", "", (byte)-1,
                (short)-1, (byte)-1, "");
    }

    private static boolean isWoodySurfaceTile(Tiles.Tile tile) {
        if (tile == null) return false;
        try { if (tile.isTree() || tile.isBush()) return true; } catch (Throwable ignored) {}
        String n = safe(tile.getName()).toLowerCase(Locale.ROOT);
        // Defensive fallback for custom-server vegetation tile definitions.
        return n.contains(" tree") || n.endsWith("tree") ||
                n.contains(" bush") || n.endsWith("bush");
    }

    private static TargetSignature findGroundObjectSignature(long targetId) {
        try {
            Object listener = hud.getWorld().getServerConnection().getServerConnectionListener();
            Object raw = getFieldValue(listener, "groundItems");
            if (!(raw instanceof Map)) return null;
            Object[] values = ((Map<?, ?>)raw).values().toArray(); // CME-safe snapshot
            for (Object value : values) {
                if (!(value instanceof GroundItemCellRenderable)) continue;
                GroundItemCellRenderable renderable = (GroundItemCellRenderable)value;
                if (renderable.getId() != targetId) continue;
                GroundItemData data = (GroundItemData)getFieldValue(renderable, "item");
                if (data == null) return null;

                String model = modelNameLikeEZVehicle(renderable, data);
                String name = safe(data.getName());
                String normalizedName = normalizeObjectName(name);
                // Preserve model/species detail for RepeatActionBot's ground
                // target discovery. Replay memory itself is GROUND_OBJECT for
                // both placed objects and mobile item-like world objects.
                boolean felledTree = isFelledTreeObject(model, normalizedName);
                String stable = felledTree ? "felled_tree" :
                        (!model.isEmpty() ? model.toLowerCase(Locale.ROOT).trim() :
                        (data.getIconId() + ":" + data.getPlaceable() + ":" + data.getMaterialId() + ":" + normalizedName));
                TargetKey key = new TargetKey(TargetKind.WORLD_GROUND_OBJECT, -1, stable);
                return new TargetSignature(key, TargetKind.WORLD_GROUND_OBJECT, InventoryLocation.NONE,
                        (byte)-1, (short)-1, name, model, normalizedName,
                        data.getMaterialId(), data.getIconId(), data.getPlaceable(),
                        renderable.getClass().getName());
            }
        } catch (Throwable t) {
            debugLog("ground object resolve failed: " + t.getMessage());
        }
        return null;
    }

    private static String normalizeObjectName(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT).trim();
        int p = n.indexOf('(');
        return p > 0 ? n.substring(0, p).trim() : n;
    }

    private static boolean isFelledTreeObject(String model, String normalizedName) {
        String n = safe(normalizedName).toLowerCase(Locale.ROOT);
        String m = safe(model).toLowerCase(Locale.ROOT);
        return n.contains("felled tree") || m.contains("felledtree") ||
                m.contains("felled.tree") || m.contains("felled_tree");
    }


    /**
     * Resolve entries from Wurm's ServerConnectionListenerClass.getCreatures().
     * Despite the collection name, it contains both actual living creatures and
     * item-like mobile objects (ships, boats, carts, wagons, etc.). Wurm exposes
     * the distinction directly on CreatureCellRenderable via isItem():
     *   false -> CREATURE
     *   true  -> WORLD_OBJECT
     *
     * This lets RepeatAction learn arbitrary actions on animals/NPCs separately
     * from arbitrary actions on mobile world objects, without name heuristics.
     */
    private static TargetSignature findCreatureMapSignature(long targetId) {
        try {
            Object listener = hud.getWorld().getServerConnection().getServerConnectionListener();
            Method getCreatures = listener.getClass().getMethod("getCreatures");
            Object raw = getCreatures.invoke(listener);
            if (!(raw instanceof Map)) return null;

            Object renderable = ((Map<?, ?>)raw).get(Long.valueOf(targetId));
            if (renderable == null) {
                // Defensive fallback for unusual/custom map implementations.
                Object[] values = ((Map<?, ?>)raw).values().toArray();
                for (Object value : values) {
                    if (value == null) continue;
                    long id = readRenderableId(value);
                    if (id == targetId) {
                        renderable = value;
                        break;
                    }
                }
            }
            if (renderable == null) return null;

            boolean itemLike = readCreatureRenderableIsItem(renderable);
            TargetKind kind = itemLike ? TargetKind.WORLD_OBJECT : TargetKind.CREATURE;

            String model = readModelName(renderable);
            String name = readRenderableName(renderable);
            String normalizedName = normalizeObjectName(name);

            // Prefer the model because renamed ships and named animals should
            // retain their semantic TYPE. Fall back to class/name only for
            // custom renderables that expose no model string.
            String stable = !model.isEmpty()
                    ? model.toLowerCase(Locale.ROOT).trim()
                    : renderable.getClass().getName().toLowerCase(Locale.ROOT) + ":" + normalizedName;

            TargetKey key = new TargetKey(kind, -1, stable);
            short icon = -1;
            try {
                Method m = renderable.getClass().getMethod("getIconId");
                Object v = m.invoke(renderable);
                if (v instanceof Number) icon = ((Number)v).shortValue();
            } catch (Throwable ignored) {}

            return new TargetSignature(key, kind, InventoryLocation.NONE,
                    (byte)-1, (short)-1, name, model, normalizedName,
                    (byte)-1, icon, (byte)-1, renderable.getClass().getName());
        } catch (Throwable t) {
            debugLog("creature/object resolve failed: " + t.getMessage());
            return null;
        }
    }

    /** Read CreatureCellRenderable.isItem() without a hard class dependency so
     * custom subclasses/proxies remain compatible. Missing isItem() defaults to
     * CREATURE; truly alien targets still have the outer MISC fallback. */
    private static boolean readCreatureRenderableIsItem(Object obj) {
        if (obj == null) return false;
        try {
            Method m = obj.getClass().getMethod("isItem");
            Object v = m.invoke(obj);
            if (v instanceof Boolean) return ((Boolean)v).booleanValue();
        } catch (Throwable ignored) {}
        return false;
    }

    private static long readRenderableId(Object obj) {
        if (obj == null) return -1L;
        try {
            Method m = obj.getClass().getMethod("getId");
            Object v = m.invoke(obj);
            return v instanceof Number ? ((Number)v).longValue() : -1L;
        } catch (Throwable ignored) { return -1L; }
    }

    private static String readRenderableName(Object obj) {
        if (obj == null) return "";
        for (String mn : new String[]{"getHoverName", "getName"}) {
            try {
                Method m = obj.getClass().getMethod(mn);
                Object v = m.invoke(obj);
                if (v != null) {
                    String n = v.toString().trim();
                    if (!n.isEmpty()) return n;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    // Same model-resolution philosophy as EZVehicle: stable renderer/data model
    // first, mutable visible name only as a final fallback.
    private static String modelNameLikeEZVehicle(Object renderable, GroundItemData data) {
        String s = readModelName(renderable);
        if (!s.isEmpty()) return s;
        return readModelName(data);
    }

    private static String readModelName(Object obj) {
        if (obj == null) return "";
        String[] methods = {"getModelName", "getModelResourceName", "getModel", "getItemModelName"};
        for (String mn : methods) {
            try {
                Method m = obj.getClass().getMethod(mn);
                Object res = m.invoke(obj);
                if (res != null) {
                    String v = res.toString().trim();
                    if (!v.isEmpty() && !v.startsWith("com.")) return v;
                }
            } catch (Throwable ignored) {}
        }
        String[] fields = {"modelName", "model", "itemModelName"};
        for (String fn : fields) {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fn);
                    f.setAccessible(true);
                    Object res = f.get(obj);
                    if (res != null) {
                        String v = res.toString().trim();
                        if (!v.isEmpty()) return v;
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) { break; }
            }
        }
        return "";
    }

    private static final class InventoryHit {
        final InventoryMetaItem item;
        final InventoryMetaItem root;
        final InventoryLocation location;
        final boolean bulk;
        InventoryHit(InventoryMetaItem item, InventoryMetaItem root,
                     InventoryLocation location, boolean bulk) {
            this.item = item;
            this.root = root;
            this.location = location;
            this.bulk = bulk;
        }
    }

    private static TargetSignature findInventoryTargetSignature(long targetId) {
        InventoryHit hit = findInventoryHit(targetId);
        if (hit == null || hit.item == null) return null;
        if (hit.bulk || isLikelyBulkPile(hit.item)) {
            debugLog("inventory target is bulk/aggregate; ignoring: " +
                    safeItemName(hit.item) + " #" + targetId);
            return new TargetSignature(
                    new TargetKey(TargetKind.IGNORED_BULK, -1, ""),
                    TargetKind.IGNORED_BULK, hit.location,
                    (byte)-1, (short)-1, safeItemName(hit.item), "", "",
                    (byte)-1, (short)-1, (byte)-1, "");
        }

        InventoryMetaItem item = hit.item;
        short type = item.getType();
        String base = normalizeBaseItemName(item.getBaseName());
        TargetKey key = inventoryItemKey(type, base, item.getMaterialId());
        return new TargetSignature(key, TargetKind.INVENTORY_ITEM, hit.location,
                (byte)-1, type, safe(item.getDisplayName()), "", base,
                item.getMaterialId(), item.getImproveIconId(), (byte)-1,
                item.getClass().getName());
    }

    private static InventoryHit findInventoryHit(long targetId) {
        // Player inventory is authoritative and does not require a UI window.
        try {
            InventoryMetaItem root = hud.getWorld().getInventoryManager().getPlayerInventory().getRootItem();
            InventoryHit hit = findInInventoryTree(root, targetId, InventoryLocation.PLAYER_INVENTORY, false);
            if (hit != null) return hit;
        } catch (Throwable ignored) {}

        // Other inventory items are only knowable while their container tree is
        // loaded/open client-side (weapon rack, chest, cart, wagon, etc.).
        for (InventoryMetaItem root : openInventoryRoots()) {
            if (root == null) continue;
            InventoryLocation location = looksLikeVehicle(root)
                    ? InventoryLocation.VEHICLE_INVENTORY
                    : InventoryLocation.ORDINARY_CONTAINER;
            boolean rootBulk = isBulkContainer(root);
            InventoryHit hit = findInInventoryTree(root, targetId, location, rootBulk);
            if (hit != null) return hit;
        }
        return null;
    }

    private static InventoryHit findInInventoryTree(InventoryMetaItem root, long targetId,
                                                     InventoryLocation location, boolean rootBulk) {
        if (root == null) return null;
        final class Node {
            final InventoryMetaItem item;
            final boolean bulk;
            Node(InventoryMetaItem item, boolean bulk) { this.item = item; this.bulk = bulk; }
        }
        ArrayDeque<Node> q = new ArrayDeque<Node>();
        Set<Long> seen = new HashSet<Long>();
        q.add(new Node(root, rootBulk || isBulkContainer(root)));
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            InventoryMetaItem item = n.item;
            if (item == null || !seen.add(item.getId())) continue;
            boolean bulkHere = n.bulk || isBulkContainer(item) || isLikelyBulkPile(item);
            if (item.getId() == targetId) return new InventoryHit(item, root, location, bulkHere);
            try {
                for (InventoryMetaItem child : item.getChildren()) {
                    if (child != null) q.addLast(new Node(child, bulkHere));
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static List<InventoryMetaItem> openInventoryRoots() {
        List<InventoryMetaItem> out = new ArrayList<InventoryMetaItem>();
        if (hud == null) return out;
        Set<Object> seenWindows = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Set<Long> seenRoots = new HashSet<Long>();
        List<Object> candidates = new ArrayList<Object>();

        Object components = getFieldValue(hud, "components");
        if (components instanceof Collection) candidates.addAll((Collection<?>)components);
        for (String field : new String[]{"inventoryWindows", "inventoryContainerWindows"}) {
            Object v = getFieldValue(hud, field);
            if (v instanceof Map) candidates.addAll(((Map<?, ?>)v).values());
        }
        try { candidates.add(hud.getInventoryWindow()); } catch (Throwable ignored) {}

        for (Object candidate : candidates) {
            if (candidate == null || !seenWindows.add(candidate)) continue;
            InventoryListComponent list = listFromWindow(candidate);
            InventoryMetaItem root = rootFromList(list);
            if (root == null && candidate instanceof InventoryContainerWindow) {
                root = rootFromContainerWindow((InventoryContainerWindow)candidate);
            }
            if (root != null && seenRoots.add(root.getId())) out.add(root);
        }
        return out;
    }

    private static InventoryListComponent listFromWindow(Object win) {
        if (win == null) return null;
        if (win instanceof InventoryListComponent) return (InventoryListComponent)win;
        if (win instanceof InventoryWindow) return ((InventoryWindow)win).getInventoryListComponent();
        Object v = getFieldValue(win, "component");
        if (v instanceof InventoryListComponent) return (InventoryListComponent)v;
        Object via = invokeZero(win, "getInventoryListComponent");
        return via instanceof InventoryListComponent ? (InventoryListComponent)via : null;
    }

    private static InventoryMetaItem rootFromList(InventoryListComponent list) {
        if (list == null) return null;
        Object view = getFieldValue(list, "inventoryWindow");
        Object root = invokeZero(view, "getRootItem");
        if (root instanceof InventoryMetaItem) return (InventoryMetaItem)root;
        Object node = getFieldValue(list, "rootItem");
        if (node instanceof InventoryMetaItem) return (InventoryMetaItem)node;
        Object item = getFieldValue(node, "item");
        return item instanceof InventoryMetaItem ? (InventoryMetaItem)item : null;
    }

    private static InventoryMetaItem rootFromContainerWindow(InventoryContainerWindow window) {
        Object node = getFieldValue(window, "rootItem");
        Object item = getFieldValue(node, "item");
        return item instanceof InventoryMetaItem ? (InventoryMetaItem)item : null;
    }

    private static Object invokeZero(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) { return null; }
        }
        return null;
    }

    private static Object getFieldValue(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) { return null; }
        }
        return null;
    }

    private static boolean isBulkContainer(InventoryMetaItem item) {
        if (item == null) return false;
        String n = safeItemName(item).toLowerCase(Locale.ROOT);
        return n.contains("bulk storage") || n.contains("food storage") ||
                n.contains("storage bin") || n.contains("bulk container") ||
                n.contains("crate");
    }

    private static boolean isLikelyBulkPile(InventoryMetaItem item) {
        if (item == null) return false;
        String display = safe(item.getDisplayName());
        return display.matches(".*\\(\\d+x\\).*");
    }

    private static boolean looksLikeVehicle(InventoryMetaItem item) {
        String n = safeItemName(item).toLowerCase(Locale.ROOT);
        return n.contains("cart") || n.contains("wagon") || n.contains("boat") ||
                n.contains("ship") || n.contains("knarr") || n.contains("corbita") ||
                n.contains("cog") || n.contains("raft");
    }

    private static String safeItemName(InventoryMetaItem item) {
        if (item == null) return "";
        String display = safe(item.getDisplayName());
        String base = safe(item.getBaseName());
        return display.isEmpty() ? base : display;
    }

    private static RawSourceType resolveActualSourceType(long sourceId) {
        // Source identity must come from the ACTUAL outgoing packet. Never infer
        // a source-less action from whatever item merely happens to be active.
        if (sourceId <= 0L) return null;
        InventoryMetaItem item = findInventoryItemById(sourceId);
        if (item == null && hud != null) {
            try {
                InventoryMetaItem active = hud.getSourceItem();
                if (active != null && active.getId() == sourceId) item = active;
            } catch (Throwable ignored) {}
        }
        if (item == null) return null;
        try {
            return new RawSourceType(item.getType(), item.getBaseName(), item.getMaterialId(), item.getWeight(), item.getDisplayName());
        } catch (Throwable ignored) { return null; }
    }

    private static InventoryMetaItem findInventoryItemById(long id) {
        if (id <= 0L || hud == null || hud.getWorld() == null) return null;
        try {
            InventoryMetaItem root = hud.getWorld().getInventoryManager().getPlayerInventory().getRootItem();
            ArrayDeque<InventoryMetaItem> todo = new ArrayDeque<InventoryMetaItem>();
            Set<Long> seen = new HashSet<Long>();
            todo.add(root);
            while (!todo.isEmpty()) {
                InventoryMetaItem item = todo.removeFirst();
                if (item == null || !seen.add(item.getId())) continue;
                if (item.getId() == id) return item;
                for (InventoryMetaItem child : item.getChildren()) if (child != null) todo.add(child);
            }
        } catch (Throwable ignored) {}
        // Source items should normally be in player inventory, but ordinary open
        // inventory windows are cheap to inspect as a fallback.
        for (InventoryMetaItem root : openInventoryRoots()) {
            InventoryHit hit = findInInventoryTree(root, id, InventoryLocation.ORDINARY_CONTAINER, false);
            if (hit != null) return hit.item;
        }
        return null;
    }

    /** Resolve the target the user is pointing/selecting at right now. */
    private static TargetSignature resolveCurrentTargetSignature() {
        if (hud == null || hud.getWorld() == null) return null;

        // UI/inventory targeting: use Wurm's own command-target lookup at the
        // current mouse coordinates. This is the authoritative path for rows in
        // player inventory, weapon racks, chests, carts, etc.
        try {
            WurmEventHandler handler = hud.getWorld().getClient().getEventHandler();
            Object xo = getFieldValue(handler, "xMouse");
            Object yo = getFieldValue(handler, "yMouse");
            if (xo instanceof Integer && yo instanceof Integer) {
                long[] ids = hud.getCommandTargetsFrom((Integer)xo, (Integer)yo);
                TargetSignature common = commonSignature(ids);
                if (common != null) return common;
            }
        } catch (Throwable t) {
            debugLog("current command target lookup failed: " + t.getMessage());
        }

        // World targeting: use current hovered PickableUnit. Cache only a real,
        // non-null world hover; never guess an action category from its class.
        try {
            Object fresh = hud.getWorld().getCurrentHoveredObject();
            if (fresh != null) lastHoveredObject = fresh;
            Object hovered = fresh != null ? fresh : lastHoveredObject;
            if (hovered instanceof PickableUnit) {
                TargetSignature sig = resolveActualTargetSignature(((PickableUnit)hovered).getId());
                if (sig != null) return sig;
            }
        } catch (Throwable t) {
            debugLog("current world target lookup failed: " + t.getMessage());
        }
        return null;
    }

    /** Exact id corresponding to the same cached/current world hover policy used
     * by resolveCurrentTargetSignature(). */
    private static long currentWorldTargetId() {
        if (hud == null || hud.getWorld() == null) return -1L;
        try {
            Object fresh = hud.getWorld().getCurrentHoveredObject();
            if (fresh != null) lastHoveredObject = fresh;
            Object hovered = fresh != null ? fresh : lastHoveredObject;
            if (hovered instanceof PickableUnit) return ((PickableUnit)hovered).getId();
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static TargetSignature commonSignature(long[] ids) {
        if (ids == null || ids.length == 0) return null;
        TargetSignature first = null;
        for (long id : ids) {
            TargetSignature sig = resolveActualTargetSignature(id);
            if (sig == null) continue;
            if (first == null) first = sig;
            else if (!first.key.equals(sig.key)) {
                debugLog("mixed target selection refused: " + first.key + " vs " + sig.key);
                return null;
            }
        }
        return first;
    }

    private static void hookRawGroundActionCapture() {
        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.comm.SimpleServerConnectionClass",
                    "sendSingleAction", "(JJLcom/wurmonline/shared/constants/PlayerAction;)V",
                    () -> (proxy, method, args) -> {
                        try {
                            long source = (Long) args[0];
                            long target = (Long) args[1];
                            PlayerAction action = (PlayerAction) args[2];
                            captureActualOutgoingAction(action.getId(), source, new long[]{target});
                        } catch (Throwable ignored) {}
                        return method.invoke(proxy, args);
                    });
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.comm.SimpleServerConnectionClass",
                    "sendAction", "(J[JLcom/wurmonline/shared/constants/PlayerAction;)V",
                    () -> (proxy, method, args) -> {
                        try {
                            long source = (Long) args[0];
                            long[] targets = (long[]) args[1];
                            PlayerAction action = (PlayerAction) args[2];
                            captureActualOutgoingAction(action.getId(), source, targets);
                        } catch (Throwable ignored) {}
                        return method.invoke(proxy, args);
                    });
            log(">>> Hooked raw outgoing action target capture");
        } catch (Throwable t) {
            log(">>> FAILED raw outgoing action target capture: " + t.getMessage());
        }
    }

    // ==================== LOGGING ====================
    /**
     * Debug log lifecycle:
     *  - debug OFF: no file is created or written by this mod;
     *  - first debug ON in each Wurm client run: create/truncate RepeatAction_Log.txt;
     *  - later debug OFF/ON toggles in the same run: append to the same file;
     *  - restarting Wurm resets debugLogStartedThisRun, so the next debug session
     *    starts a fresh file again.
     */
    private static void ensureDebugLogStarted() {
        if (!debug || debugLogStartedThisRun) return;
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", false);
            writer.close();
            debugLogStartedThisRun = true;
        } catch (Exception ignored) {}
    }

    private static void log(String message) {
        if (!debug) return;
        ensureDebugLogStarted();
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", true);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception ignored) {}
    }

    private static void debugLog(String message) {
        log(message);
    }

    /** Queue console text for the Wurm game thread. Never call hud.consoleOutput()
     * directly from an arbitrary mod/bot thread. */
    private static void console(String message) {
        if (message == null || message.isEmpty()) return;
        CONSOLE_QUEUE.offer(message);
    }

    private static void drainConsoleQueue() {
        HeadsUpDisplay h = hud;
        if (h == null) return;
        for (int i = 0; i < MAX_CONSOLE_LINES_PER_TICK; i++) {
            String line = CONSOLE_QUEUE.poll();
            if (line == null) break;
            try { h.consoleOutput(line); }
            catch (Throwable t) {
                // Do not put the failed line back: a broken HUD must not create
                // an infinite retry loop. File logging remains available.
                logger.log(Level.FINE, "consoleOutput failed", t);
            }
        }
    }

    private static void hookSafeConsoleDrain() {
        try {
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.game.World",
                    "tick", "()V",
                    () -> (proxy, method, args) -> {
                        Object result = method.invoke(proxy, args);
                        drainConsoleQueue();
                        return result;
                    }
            );
            log(">>> Hooked safe game-thread console drain");
        } catch (Throwable t) {
            log(">>> FAILED safe console drain hook: " + t.getMessage());
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

            return searchInventoryBest(inventoryRoot, searchTerm.toLowerCase().trim());
        } catch (Exception e) {
            if (debug) debugLog("Search error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Score how well an item name matches the search term.
     * Higher = better. 0 = no match.
     *
     * Priority:
     *   4  exact display or base match
     *   3  display/base starts with full term, or full term starts with display/base
     *   2  display/base contains the full term (or vice-versa for substantial names)
     *   1  core-type match only (everything before first comma) — last resort
     */
    private static int matchScore(String display, String base, String term, String coreType) {
        if (display.isEmpty() && base.isEmpty()) return 0;

        if (display.equals(term) || base.equals(term)) return 4;

        if ((!display.isEmpty() && (display.startsWith(term) || term.startsWith(display))) ||
            (!base.isEmpty() && (base.startsWith(term) || term.startsWith(base)))) {
            return 3;
        }

        if (display.contains(term) || base.contains(term) ||
            (base.length() > 2 && term.contains(base)) ||
            (display.length() > 2 && term.contains(display))) {
            return 2;
        }

        if (coreType.length() > 2 &&
            (display.contains(coreType) || base.contains(coreType))) {
            return 1;
        }

        return 0;
    }

    private static float getItemQuality(InventoryMetaItem item) {
        try {
            // InventoryMetaItem.getQuality() returns the QL as float
            return item.getQuality();
        } catch (Exception e) {
            return Float.MAX_VALUE; // unknown → treat as highest so real QLs win
        }
    }

    private static InventoryMetaItem searchInventoryBest(InventoryMetaItem root, String term) {
        String coreType = term;
        int comma = term.indexOf(',');
        if (comma > 0) {
            coreType = term.substring(0, comma).trim();
        }

        InventoryMetaItem best = null;
        int bestScore = 0;
        float bestQl = Float.MAX_VALUE;

        // Iterative stack-based walk so we can score every candidate
        java.util.ArrayDeque<InventoryMetaItem> stack = new java.util.ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            InventoryMetaItem item = stack.pop();
            try {
                String base = item.getBaseName() != null ? item.getBaseName().toLowerCase().trim() : "";
                String display = item.getDisplayName() != null ? item.getDisplayName().toLowerCase().trim() : "";

                int score = matchScore(display, base, term, coreType);
                if (score > 0) {
                    float ql = getItemQuality(item);
                    // Prefer higher name-match score; on a tie pick the lowest QL
                    if (score > bestScore || (score == bestScore && ql < bestQl)) {
                        bestScore = score;
                        bestQl = ql;
                        best = item;
                    }
                }
            } catch (Exception ignored) {}

            try {
                for (InventoryMetaItem child : item.getChildren()) {
                    stack.push(child);
                }
            } catch (Exception ignored) {}
        }

        return best;
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
                                        // Active-source changes are NOT a reason to forget learned actions.
                                        // Target/action recognition is independent of which inventory item is
                                        // currently active.  This label is for diagnostics ONLY:
                                        // replacement is controlled by the actual active-item identity.
                                        boolean changed = lastItemBaseName == null || !lastItemBaseName.equals(name);
                                        lastItemBaseName = name;
                                        if (changed) {
                                            debugLog("ITEM ACTIVATED → \"" + name + "\" (action cache preserved)");
                                        }
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
                        InventoryMetaItem removed = (args != null && args.length > 0
                                && args[0] instanceof InventoryMetaItem)
                                ? (InventoryMetaItem) args[0] : null;

                        // Wurm invokes removeActiveToolItem for inventory removals
                        // including items that are merely ACTION TARGETS. The native
                        // method only clears the active source when the EXACT same
                        // InventoryMetaItem object is removed (reference equality).
                        // Mirror that condition; never choose a new source because
                        // some other inventory item was consumed or removed.
                        boolean removedWasActive = false;
                        if (removed != null) {
                            try {
                                removedWasActive = ((HeadsUpDisplay) proxy).getSourceItem() == removed;
                            } catch (Throwable t) {
                                debugLog("active-source check failed: " + t.getMessage());
                            }
                        }

                        // Let Wurm clear the consumed ACTIVE source FIRST. Replacing
                        // it before this call would cause Wurm to clear the new item,
                        // or leave the HUD in an inconsistent state.
                        Object result = method.invoke(proxy, args);

                        if (removedWasActive && removed != null) {
                            try {
                                HeadsUpDisplay display = (HeadsUpDisplay) proxy;
                                // Another hook may have already selected a valid
                                // replacement. Never override an active source.
                                if (display.getSourceItem() == null) {
                                    RawSourceType removedType = new RawSourceType(
                                            removed.getType(), removed.getBaseName(),
                                            removed.getMaterialId(), removed.getWeight(),
                                            removed.getDisplayName());

                                    // Exclude the removed ID. The inventory tree may
                                    // still contain it while removal callbacks run.
                                    InventoryMetaItem nextItem = findMatchingSourceInPlayer(
                                            removedType, true, removed.getId());
                                    if (nextItem != null && activateSourceItem(nextItem)) {
                                        debugLog("CONSUMED ACTIVE SOURCE exact replacement selected → \"" +
                                                safe(nextItem.getDisplayName()) + "\" #" + nextItem.getId());
                                    } else {
                                        debugLog("CONSUMED ACTIVE SOURCE exact replacement unavailable → " +
                                                removedType.sourceLabel() +
                                                "; compatible cross-material fallback deferred");
                                    }
                                }
                            } catch (Throwable t) {
                                debugLog("active-source replacement error: " + t.getMessage());
                            }
                        }
                        return result;
                    }
            );
            log(">>> Hooked removeActiveToolItem successfully");
        } catch (Exception e) {
            log(">>> FAILED to hook removeActiveToolItem");
        }
    }

    // ==================== COMMANDS ====================
    public static final int REPEAT_RESULT_FAILED = -1;
    public static final int REPEAT_RESULT_NO_ACTION = 0;
    public static final int REPEAT_RESULT_SENT = 1;
    public static final int REPEAT_RESULT_BLOCKED = 2;
    // The action is known, but its learned source item is no longer available
    // in the player's inventory. RepeatActionBot may now retrieve more from
    // external storage; RepeatAction itself deliberately does not bot storage.
    public static final int REPEAT_RESULT_SOURCE_MISSING = 3;

    /**
     * Direct bot-safe replay API.  RepeatActionBot uses this instead of
     * scraping RepeatAction_Log.txt, so normal debug-off operation is fully
     * independent of per-action logging.  The current target is resolved using
     * the same target-type logic as the ordinary console command.
     */
    public static int repeatCurrentTargetForBot() {
        return repeatCurrentTarget(true, false);
    }

    /** Bot fallback path used only after RepeatActionBot has already searched
     * external storage for the originally-captured material/species. */
    public static int repeatCurrentTargetForBotGeneralSource() {
        return repeatCurrentTarget(true, true);
    }

    /**
     * Ground-bot replay API: send the learned action to the EXACT Wurm target
     * id supplied by RepeatActionBot.  This deliberately bypasses
     * World.sendHoveredAction(), because synthetic hover state is not a reliable
     * command target once HUD mouse/UI targeting is involved.
     */
    public static int repeatTargetForBot(long targetId) {
        return repeatExactTarget(targetId, true, false);
    }

    /** Exact-target bot fallback that permits a generalized compatible source
     * (for example walnut sprout after cedarwood sprouts are exhausted). */
    public static int repeatTargetForBotGeneralSource(long targetId) {
        return repeatExactTarget(targetId, true, true);
    }

    /** Read-only helper useful for compatibility/debugging. */
    public static short getCurrentTargetLearnedActionId() {
        TargetSignature current = resolveCurrentTargetSignature();
        if (current == null) return -1;
        Short learned = learnedActions.get(current.key);
        return learned == null ? (short)-1 : learned.shortValue();
    }

    private static RawSourceType learnedSourceFor(TargetKey key) {
        return key == null ? null : learnedSources.get(key);
    }

    private static RawSourceType learnedSourceForTargetId(long targetId) {
        TargetSignature t = resolveActualTargetSignature(targetId);
        return t == null ? null : learnedSourceFor(t.key);
    }

    private static RawSourceType learnedSourceForCurrentTarget() {
        TargetSignature t = resolveCurrentTargetSignature();
        return t == null ? null : learnedSourceFor(t.key);
    }

    public static short getCurrentTargetSourceType() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? (short)-1 : s.type;
    }
    public static String getCurrentTargetSourceBaseName() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? "" : s.baseName;
    }
    public static byte getCurrentTargetSourceMaterial() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? (byte)-1 : s.material;
    }
    public static byte getCurrentTargetSourceExactMaterial() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? (byte)-1 : s.exactMaterial;
    }
    public static String getCurrentTargetSourceDisplayName() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? "" : s.sourceLabel();
    }
    public static float getCurrentTargetSourceWeight() {
        RawSourceType s = learnedSourceForCurrentTarget();
        return s == null ? -1f : s.weight;
    }
    public static short getTargetSourceType(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? (short)-1 : s.type;
    }
    public static String getTargetSourceBaseName(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? "" : s.baseName;
    }
    public static byte getTargetSourceMaterial(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? (byte)-1 : s.material;
    }
    public static byte getTargetSourceExactMaterial(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? (byte)-1 : s.exactMaterial;
    }
    public static String getTargetSourceDisplayName(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? "" : s.sourceLabel();
    }
    public static float getTargetSourceWeight(long targetId) {
        RawSourceType s = learnedSourceForTargetId(targetId);
        return s == null ? -1f : s.weight;
    }

    private static InventoryMetaItem playerInventoryRoot() {
        if (hud == null || hud.getWorld() == null) return null;
        try { return hud.getWorld().getInventoryManager().getPlayerInventory().getRootItem(); }
        catch (Throwable ignored) { return null; }
    }

    private static InventoryMetaItem findMatchingSourceInPlayer(RawSourceType wanted) {
        return findMatchingSourceInPlayer(wanted, false);
    }

    private static InventoryMetaItem findMatchingSourceInPlayer(RawSourceType wanted, boolean exactOnly) {
        return findMatchingSourceInPlayer(wanted, exactOnly, -1L);
    }

    private static InventoryMetaItem findMatchingSourceInPlayer(
            RawSourceType wanted, boolean exactOnly, long excludedItemId) {
        if (wanted == null) return null;
        InventoryMetaItem root = playerInventoryRoot();
        if (root == null) return null;
        ArrayDeque<InventoryMetaItem> todo = new ArrayDeque<InventoryMetaItem>();
        Set<Long> seen = new HashSet<Long>();
        todo.add(root);
        while (!todo.isEmpty()) {
            InventoryMetaItem item = todo.removeFirst();
            if (item == null || !seen.add(item.getId())) continue;
            boolean match = exactOnly ? wanted.matchesExact(item) : wanted.matches(item);
            if (item != root && item.getId() != excludedItemId && match) {
                try {
                    // Aggregated/group rows are not activatable source items.
                    if (item.getChildren() == null || item.getChildren().isEmpty()) return item;
                } catch (Throwable ignored) { return item; }
            }
            try {
                if (item.getChildren() != null)
                    for (InventoryMetaItem child : item.getChildren()) if (child != null) todo.add(child);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean activateSourceItem(InventoryMetaItem item) {
        if (item == null || hud == null) return false;
        for (Class<?> c = hud.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("setActiveToolItem", InventoryMetaItem.class);
                m.setAccessible(true);
                m.invoke(hud, item);
                try {
                    InventoryMetaItem active = hud.getSourceItem();
                    return active != null && active.getId() == item.getId();
                } catch (Throwable ignored) { return true; }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) { return false; }
        }
        return false;
    }

    /**
     * Keep RepeatAction's responsibility intentionally small: if the learned
     * source was consumed, replace it from PLAYER inventory only. External
     * wagon/cart/BSB/crate retrieval belongs to RepeatActionBot.
     */
    private static boolean ensurePlayerSourceReady(TargetKey key, boolean botMode, boolean allowGeneralFallback) {
        RawSourceType wanted = learnedSourceFor(key);
        if (wanted == null) return true; // source-less action

        InventoryMetaItem active = null;
        try { active = hud.getSourceItem(); } catch (Throwable ignored) {}
        if (active != null && wanted.matchesExact(active)) {
            InventoryMetaItem live = findInventoryItemById(active.getId());
            if (live != null && wanted.matchesExact(live)) return true;
        }

        InventoryMetaItem replacement = findMatchingSourceInPlayer(wanted, true);
        if (replacement != null && activateSourceItem(replacement)) {
            debugLog("SOURCE REPLACED FROM PLAYER INVENTORY (exact) → " + wanted.sourceLabel() +
                    " #" + replacement.getId());
            return true;
        }

        // In bot mode, stop here first.  This is the handoff point that lets
        // RepeatActionBot search loose wagon/cart cargo, children, BSBs and
        // crates for the SAME material/species before we accept another one.
        if (botMode && wanted.hasGeneralFallback() && !allowGeneralFallback) {
            debugLog("EXACT SOURCE MISSING; BOT EXTERNAL SEARCH GETS PRIORITY → " + wanted.sourceLabel());
            return false;
        }

        // Manual RepeatAction, or the bot's explicit second-stage fallback after
        // exact external storage has been exhausted, may use any compatible
        // material when the properties policy says the base item is generalized.
        if (active != null && wanted.matches(active)) {
            InventoryMetaItem live = findInventoryItemById(active.getId());
            if (live != null && wanted.matches(live)) return true;
        }

        replacement = findMatchingSourceInPlayer(wanted, false);
        if (replacement != null && activateSourceItem(replacement)) {
            debugLog("SOURCE REPLACED FROM PLAYER INVENTORY (general fallback) → " +
                    safe(replacement.getDisplayName()) + " #" + replacement.getId());
            return true;
        }

        debugLog("SOURCE MISSING FROM PLAYER INVENTORY → " + wanted.sourceLabel() +
                " [type=" + wanted.type + ", exactMaterial=" + (wanted.exactMaterial & 0xff) + "]");
        return false;
    }

    /** Include the actual active source in debug/console repeat lines. */
    private static String sourceLogSuffix(TargetKey key) {
        RawSourceType learned = learnedSourceFor(key);
        if (learned == null) return " | source=<none>";
        try {
            InventoryMetaItem active = hud == null ? null : hud.getSourceItem();
            if (active != null) {
                String display = safe(active.getDisplayName()).trim();
                if (display.isEmpty()) display = safe(active.getBaseName()).trim();
                return " | source=" + (display.isEmpty() ? learned.sourceLabel() : display) +
                        " [id=" + active.getId() + ", material=" + (active.getMaterialId() & 0xff) + "]";
            }
        } catch (Throwable ignored) {}
        return " | source=" + learned.sourceLabel() + " [not active]";
    }

    private static String missingSourceText(TargetKey key) {
        RawSourceType learned = learnedSourceFor(key);
        if (learned == null) return "Required source item is not available in player inventory.";
        return "Required source item '" + learned.sourceLabel() + "' is not available in player inventory.";
    }

    private static int repeatExactTarget(long targetId, boolean botMode, boolean allowGeneralSource) {
        TargetSignature target = resolveActualTargetSignature(targetId);
        if (target != null && target.kind == TargetKind.IGNORED_BULK) {
            debugLog("Exact-target replay refused: bulk-container row #" + targetId);
            return REPEAT_RESULT_NO_ACTION;
        }
        if (target == null) {
            String msg = "No suitable action captured for current target yet.";
            log(msg);
            console(">>> " + msg);
            debugLog("Exact-target replay could not resolve targetId=" + targetId);
            return REPEAT_RESULT_NO_ACTION;
        }

        Short learned = learnedActions.get(target.key);
        short actionToRepeat = learned == null ? (short)-1 : learned.shortValue();
        if (actionToRepeat <= 0) {
            String msg = "No suitable action captured for current target yet.";
            log(msg);
            console(">>> " + msg);
            debugLog("No learned action for exact target=" + target.describe() +
                    " key=" + target.key + " id=" + targetId);
            return REPEAT_RESULT_NO_ACTION;
        }

        if (botMode && actionToRepeat == BOT_IGNORE_EAT) {
            if (lastBotBlockedActionId != actionToRepeat) {
                String msg = "RepeatActionBot ignored Eat (action " + actionToRepeat + ").";
                log(msg);
                console(">>> " + msg);
                lastBotBlockedActionId = actionToRepeat;
            }
            return REPEAT_RESULT_BLOCKED;
        }

        lastBotBlockedActionId = -1;

        if (!ensurePlayerSourceReady(target.key, botMode, allowGeneralSource)) {
            if (!botMode) console(">>> " + missingSourceText(target.key));
            return REPEAT_RESULT_SOURCE_MISSING;
        }

        try {
            PlayerAction action = new PlayerAction(actionToRepeat, PlayerAction.ANYTHING, "", false);
            isRepeating = true;
            try {
                // HeadsUpDisplay.sendAction(action,targetId) uses the currently
                // active source item and sends this exact target to the server.
                hud.sendAction(action, targetId);
            } finally {
                isRepeating = false;
            }

            String msg = "Repeated action ID: " + actionToRepeat + " (" + target.describe() +
                    ", exact target " + targetId + ")" + sourceLogSuffix(target.key);
            debugLog(msg);
            if (debug) console(">>> " + msg);
            return REPEAT_RESULT_SENT;
        } catch (Throwable e) {
            isRepeating = false;
            String msg = "Failed to repeat action.";
            log(msg);
            debugLog("exact-target repeat failure for " + target.describe() +
                    " id=" + targetId + ": " + e.getMessage());
            console(">>> " + msg);
            return REPEAT_RESULT_FAILED;
        }
    }

    private static int repeatCurrentTarget(boolean botMode, boolean allowGeneralSource) {
        TargetSignature current = resolveCurrentTargetSignature();
        if (current != null && current.kind == TargetKind.IGNORED_BULK) {
            debugLog("Current-target replay refused: bulk-container row");
            if (!botMode) console(">>> RepeatAction ignores contents of bulk containers.");
            return REPEAT_RESULT_NO_ACTION;
        }
        if (current == null) {
            String msg = "No suitable action captured for current target yet.";
            log(msg);
            console(">>> " + msg);
            return REPEAT_RESULT_NO_ACTION;
        }

        Short learned = learnedActions.get(current.key);
        short actionToRepeat = learned == null ? (short)-1 : learned.shortValue();
        if (actionToRepeat <= 0) {
            // Preserve this exact text for humans/older integrations even
            // though RepeatActionBot v2+ now uses the direct result code.
            String msg = "No suitable action captured for current target yet.";
            log(msg);
            console(">>> " + msg);
            debugLog("No learned action for target=" + current.describe() + " key=" + current.key);
            return REPEAT_RESULT_NO_ACTION;
        }

        // Eat remains the one automation-only block. Other action IDs are
        // learned according to the target's broad category.
        if (botMode && actionToRepeat == BOT_IGNORE_EAT) {
            if (lastBotBlockedActionId != actionToRepeat) {
                String msg = "RepeatActionBot ignored Eat (action " + actionToRepeat + ").";
                log(msg);
                console(">>> " + msg);
                lastBotBlockedActionId = actionToRepeat;
            }
            return REPEAT_RESULT_BLOCKED;
        }

        lastBotBlockedActionId = -1;

        if (!ensurePlayerSourceReady(current.key, botMode, allowGeneralSource)) {
            if (!botMode) console(">>> " + missingSourceText(current.key));
            return REPEAT_RESULT_SOURCE_MISSING;
        }

        try {
            PlayerAction action = new PlayerAction(actionToRepeat, PlayerAction.ANYTHING, "", false);
            isRepeating = true;
            try {
                if (current.kind == TargetKind.CREATURE ||
                        current.kind == TargetKind.WORLD_OBJECT || current.kind == TargetKind.MISC) {
                    long exactId = currentWorldTargetId();
                    TargetSignature exact = exactId > 0 ? resolveActualTargetSignature(exactId) : null;
                    if (exact != null && current.key.equals(exact.key)) {
                        // Creature/object interactions and unknown/custom MISC
                        // targets are safest when replayed to the concrete target id,
                        // rather than depending on Wurm to reconstruct hover.
                        hud.sendAction(action, exactId);
                    } else {
                        hud.getWorld().sendHoveredAction(action);
                    }
                } else {
                    hud.getWorld().sendHoveredAction(action);
                }
            } finally {
                isRepeating = false;
            }

            String msg = "Repeated action ID: " + actionToRepeat + " (" + current.describe() + ")" +
                    sourceLogSuffix(current.key);
            debugLog(msg);
            if (debug) console(">>> " + msg);
            return REPEAT_RESULT_SENT;

        } catch (Throwable e) {
            isRepeating = false;
            String msg = "Failed to repeat action.";
            log(msg);
            debugLog("repeat failure for " + current.describe() + ": " + e.getMessage());
            console(">>> " + msg);
            return REPEAT_RESULT_FAILED;
        }
    }

    public static boolean handleInput(final String cmd, final String[] data) {

        String cleanCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;

        if (cleanCmd.equalsIgnoreCase("repeataction")) {
            boolean botMode = false;
            if (data != null) {
                for (String token : data) {
                    if ("bot".equalsIgnoreCase(token)) {
                        botMode = true;
                        break;
                    }
                }
            }
            repeatCurrentTarget(botMode, false);
            return true;
        }

        if (cleanCmd.equalsIgnoreCase("repeataction_debug")) {
            if (!debug) {
                debug = true;
                boolean firstDebugSessionThisRun = !debugLogStartedThisRun;
                ensureDebugLogStarted();
                log(">>> RepeatAction v" + VERSION + " debug session started");
                String msg = firstDebugSessionThisRun
                        ? "Debug mode ENABLED (started fresh RepeatAction_Log.txt for this Wurm run; per-action console + file logging active)"
                        : "Debug mode ENABLED (continuing RepeatAction_Log.txt for this Wurm run; per-action console + file logging active)";
                log(msg);
                console(">>> " + msg);
            } else {
                String msg = "Debug mode DISABLED (per-action console/log spam suppressed)";
                // Record the end of this debug session while the logger is still enabled.
                log(msg);
                debug = false;
                console(">>> " + msg);
            }
            return true;
        }

        return false;
    }

    /**
     * Kept as a compatibility entry point for older hooks/other mods. Learning
     * no longer happens here: without the packet target IDs a PlayerAction alone
     * cannot tell us what it was actually performed on.
     */
    public static void recordAction(PlayerAction action) {
        if (!debug || action == null || isRepeating) return;
        try {
            debugLog("HUD action observed id=" + action.getId() + " (not learned; waiting for outgoing target packet)");
        } catch (Throwable ignored) {}
    }

    private static void loadIgnoreList() {
        ignoredActions.clear();
        materialSpecificItems.clear();
        // Always keep ores and lumps separated by material.
        materialSpecificItems.add("ore");
        materialSpecificItems.add("lump");

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

            // Comma-separated GENERAL base names.  Example:
            // materialSpecificItems=sprout,log,wood scrap
            // Ore and lump are always included even if this property is blank.
            String specific = props.getProperty("materialSpecificItems",
                    props.getProperty("material_specific_items", ""));
            for (String name : specific.split(",")) {
                String base = normalizeBaseItemName(name);
                if (!base.isEmpty()) materialSpecificItems.add(base);
            }

            log(">>> Ignore list loaded (" + ignoredActions.size() + " actions); material-specific items=" +
                    materialSpecificItems);
        } catch (Exception e) {
            log("WARNING: Could not load properties file");
        }
    }

    @Override
    public void init() {
        // Debug starts OFF. Do not create RepeatAction_Log.txt during normal startup.
        // debugLogStartedThisRun is process-local and therefore naturally resets
        // on every Wurm client start.
        debugLogStartedThisRun = false;
        logger.info("RepeatAction v" + VERSION + " loaded (debug off)");
        loadIgnoreList();
        hookSafeConsoleDrain();
        hookActiveTool();
        hookRemoveActiveToolItem();
        hookRawGroundActionCapture();

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
    public void preInit() {
        registerBotBridge();
    }
}
