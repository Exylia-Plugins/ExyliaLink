package net.exylia.link.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * The server's threads: the main thread on Spigot and older Paper, the global region and each
 * player's own region where the server has those schedulers (Folia, and Paper since 1.20, where
 * they map onto the main thread). Compiled against 1.17, so they are reached reflectively; without
 * them the Bukkit scheduler is used as it always was.
 */
final class Schedulers {

    private static final Object GLOBAL = global();
    private static final Method RUN = method("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",
            "run", Plugin.class, Consumer.class);
    private static final Method ENTITY_SCHEDULER = GLOBAL == null ? null : method(Entity.class.getName(), "getScheduler");
    private static final Method ENTITY_RUN = method("io.papermc.paper.threadedregions.scheduler.EntityScheduler",
            "run", Plugin.class, Consumer.class, Runnable.class);

    private Schedulers() {
    }

    /** On the main thread, or Folia's global region (console commands belong there). */
    static void run(Plugin plugin, Runnable task) {
        if (GLOBAL != null && RUN != null) {
            try {
                RUN.invoke(GLOBAL, plugin, (Consumer<Object>) scheduled -> task.run());
                return;
            } catch (ReflectiveOperationException | RuntimeException refused) {
                // Falls through to the Bukkit scheduler, which Folia refuses loudly if it ever gets here.
            }
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** On the thread that owns this entity: its region on Folia, the main thread elsewhere. Dropped once it is gone. */
    static void runAt(Plugin plugin, Entity entity, Runnable task) {
        if (ENTITY_SCHEDULER != null && ENTITY_RUN != null) {
            try {
                ENTITY_RUN.invoke(ENTITY_SCHEDULER.invoke(entity), plugin, (Consumer<Object>) scheduled -> task.run(), null);
                return;
            } catch (ReflectiveOperationException | RuntimeException refused) {
                return;
            }
        }
        run(plugin, task);
    }

    private static Object global() {
        try {
            return Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notFolia) {
            return null;
        }
    }

    private static Method method(String type, String name, Class<?>... parameters) {
        if (GLOBAL == null) {
            return null;
        }
        try {
            return Class.forName(type).getMethod(name, parameters);
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }
}
