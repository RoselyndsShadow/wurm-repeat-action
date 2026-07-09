package net.roselyndsshadow.repeataction;

import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.shared.constants.PlayerAction;
import javassist.CtClass;
import javassist.CtMethod;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RepeatAction implements WurmClientMod, Initable, PreInitable {

    private static final Logger logger = Logger.getLogger(RepeatAction.class.getName());
    public static HeadsUpDisplay hud;

    private static short lastActionId = -1;
    private static boolean debug = false;

    // ==================== FILE LOGGING ====================
    private static void clearAndLog(String message) {
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", false);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception e) {
            logger.warning("Could not write to RepeatAction_Log.txt");
        }
    }

    private static void log(String message) {
        try {
            FileWriter writer = new FileWriter("RepeatAction_Log.txt", true);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            writer.write("[" + time + "] " + message + System.lineSeparator());
            writer.close();
        } catch (Exception e) {
            logger.warning("Could not write to RepeatAction_Log.txt");
        }
    }
    // =====================================================

    public static boolean handleInput(final String cmd, final String[] data) {

        // Clean the command (remove leading "/" if present)
        String cleanCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;

        // ==================== /repeataction ====================
        if (cleanCmd.equalsIgnoreCase("repeataction")) {

            if (lastActionId <= 0) {
                String msg = "No action captured yet.";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
                return true;
            }

            try {
                PlayerAction action = new PlayerAction(lastActionId, PlayerAction.ANYTHING, "", false);
                hud.getWorld().sendHoveredAction(action);

                String msg = "Repeated action ID: " + lastActionId;
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);

            } catch (Exception e) {
                String msg = "Failed to repeat action.";
                log(msg);
                if (hud != null) hud.consoleOutput(">>> " + msg);
            }
            return true;
        }

        // ==================== /repeataction_debug ====================
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
            lastActionId = action.getId();
            if (debug) {
                log("ACTION → ID: " + lastActionId);
            }
        } catch (Exception e) {
            if (debug) {
                log("Error recording action: " + e.getMessage());
            }
        }
    }

    @Override
    public void init() {
        clearAndLog(">>> RepeatAction loaded");

        try {
            // HUD hook
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                    "init",
                    "(II)V",
                    () -> (proxy, method, args) -> {
                        method.invoke(proxy, args);
                        hud = (HeadsUpDisplay) proxy;
                        if (hud != null) log(">>> HUD ready");
                        return null;
                    }
            );

            // Console hook
            HookManager.getInstance().registerHook(
                    "com.wurmonline.client.console.WurmConsole",
                    "handleDevInput",
                    "(Ljava/lang/String;[Ljava/lang/String;)Z",
                    () -> (proxy, method, args) -> {
                        if (handleInput((String) args[0], (String[]) args[1])) return true;
                        return method.invoke(proxy, args);
                    }
            );

        } catch (Throwable e) {
            log("ERROR in init: " + e.getMessage());
            logger.log(Level.SEVERE, "Error in RepeatAction init", e);
        }
    }

    @Override
    public void preInit() {
        // Dynamic hook on HeadsUpDisplay for action capture
        try {
            CtClass ctHUD = HookManager.getInstance().getClassPool()
                    .getCtClass("com.wurmonline.client.renderer.gui.HeadsUpDisplay");

            for (CtMethod method : ctHUD.getDeclaredMethods()) {
                try {
                    for (CtClass param : method.getParameterTypes()) {
                        if (param.getName().equals("com.wurmonline.shared.constants.PlayerAction")) {

                            String descriptor = method.getMethodInfo().getDescriptor();

                            HookManager.getInstance().registerHook(
                                    "com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                                    method.getName(),
                                    descriptor,
                                    () -> (proxy, m, args) -> {
                                        for (Object arg : args) {
                                            if (arg instanceof PlayerAction) {
                                                recordAction((PlayerAction) arg);
                                                break;
                                            }
                                        }
                                        return m.invoke(proxy, args);
                                    }
                            );
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

        } catch (Throwable e) {
            log("ERROR setting up action hooks: " + e.getMessage());
        }
    }
}