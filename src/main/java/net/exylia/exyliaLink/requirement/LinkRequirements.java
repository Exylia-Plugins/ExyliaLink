package net.exylia.exyliaLink.requirement;

import com.google.gson.JsonElement;
import net.exylia.exyliaLink.ExyliaLink;
import net.exylia.exyliaLink.config.LinkMessages;
import net.exylia.exyliaLink.link.LinkCache;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.text.Values;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the network's {@link Requirements} on the players here: a warning with a clickable
 * {@code /link} once a player is known to need it, a reminder every few minutes until they link,
 * and in restrict mode the listed commands refused. Re-checked after {@code link.status} on join,
 * on {@code link.updated}, on a group change and when the requirements change. Keeps the last
 * requirements and statuses it knew while the socket is down, and then never blocks.
 */
public final class LinkRequirements implements Listener {

    public static final String BYPASS = "exylialink.requirements.bypass";

    private final ExyliaLink plugin;
    private volatile Requirements current = Requirements.NONE;
    /** Players already warned; a reminder timer runs for each while reminders are on. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TaskHandle> reminders = new ConcurrentHashMap<>();

    public LinkRequirements(@NotNull ExyliaLink plugin) {
        this.plugin = plugin;
    }

    /** {@code welcome.requirements} or a {@code requirements.updated} frame; everyone is checked again. */
    public void update(@Nullable JsonElement json) {
        Requirements next = Requirements.of(json);
        if (next.equals(current)) {
            return;
        }
        current = next;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            stop(player.getUniqueId());
            check(player.getUniqueId());
        }
    }

    /** From any thread: decides on the player's own thread. */
    public void check(@NotNull UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && current.active()) {
            plugin.tasks().runAtEntity(player, () -> evaluate(player));
        } else if (player != null) {
            stop(id);
        }
    }

    public void close() {
        for (UUID id : List.copyOf(warned)) {
            stop(id);
        }
    }

    private void evaluate(Player player) {
        UUID id = player.getUniqueId();
        if (!player.isOnline() || !mustLink(player)) {
            stop(id);
            return;
        }
        if (!warned.add(id)) {
            return;
        }
        Requirements requirements = current;
        LinkMessages.Requirements lines = plugin.messages().requirements();
        plugin.say(player, requirements.mode() == Requirements.Mode.RESTRICT ? lines.warningRestricted() : lines.warning(), reason(requirements));
        long period = 20L * 60 * plugin.settings().reminderMinutes();
        if (period > 0) {
            reminders.put(id, plugin.tasks().runAtEntityTimer(player, period, period, task -> {
                if (mustLink(player)) {
                    plugin.say(player, plugin.messages().requirements().reminder(), reason(current));
                } else {
                    stop(id);
                }
            }));
        }
    }

    private boolean mustLink(Player player) {
        return current.mustLink(plugin.presence().known(player.getUniqueId()), linked(player), player.hasPermission(BYPASS));
    }

    private @Nullable Boolean linked(Player player) {
        LinkCache.Status status = plugin.cache().get(player.getUniqueId());
        return status == null ? null : status.linked();
    }

    private Values reason(Requirements requirements) {
        return requirements.message() == null
                ? Values.of("reason", plugin.messages().requirements().reason())
                : Values.of().putText("reason", requirements.message());
    }

    private void stop(UUID id) {
        warned.remove(id);
        TaskHandle reminder = reminders.remove(id);
        if (reminder != null) {
            reminder.cancel();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Requirements requirements = current;
        // Cheap test first: most commands are not restricted at all.
        if (!requirements.restricts(event.getMessage())) {
            return;
        }
        Player player = event.getPlayer();
        if (requirements.blocks(event.getMessage(), plugin.presence().known(player.getUniqueId()), linked(player),
                player.hasPermission(BYPASS), plugin.connection().welcomed())) {
            event.setCancelled(true);
            plugin.say(player, plugin.messages().requirements().blocked(), reason(requirements));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }
}
