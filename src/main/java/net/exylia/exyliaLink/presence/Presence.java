package net.exylia.exyliaLink.presence;

import net.exylia.exyliaLink.ExyliaLink;
import net.exylia.exyliaLink.gateway.Frames;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online here and in which LuckPerms groups: {@code player.join}, {@code player.quit},
 * {@code player.groups} (debounced 1 s, sent only when the list changed) and the full list for
 * {@code hello} and the five-minute {@code presence.snapshot}. Never an address.
 */
public final class Presence implements Listener {

    private static final long DEBOUNCE_TICKS = 20L;

    private final ExyliaLink plugin;
    private final LuckPermsGroups luckPerms;
    /** The groups the gateway was last told, per online player. */
    private final Map<UUID, List<String>> sent = new ConcurrentHashMap<>();
    private final Set<UUID> debouncing = ConcurrentHashMap.newKeySet();

    public Presence(@NotNull ExyliaLink plugin) {
        this.plugin = plugin;
        this.luckPerms = Bukkit.getPluginManager().isPluginEnabled("LuckPerms") ? new LuckPermsGroups() : null;
        if (luckPerms != null) {
            luckPerms.listen(plugin, this::groupsMayHaveChanged);
        }
    }

    public void close() {
        if (luckPerms != null) {
            luckPerms.close();
        }
    }

    public @NotNull List<Frames.OnlinePlayer> online() {
        List<Frames.OnlinePlayer> players = new ArrayList<>();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            players.add(entry(player));
        }
        return players;
    }

    private Frames.OnlinePlayer entry(Player player) {
        List<String> groups = groups(player.getUniqueId());
        sent.put(player.getUniqueId(), groups);
        return new Frames.OnlinePlayer(player.getUniqueId(), player.getName(), groups);
    }

    private List<String> groups(UUID player) {
        try {
            return luckPerms == null ? List.of() : Frames.clip(luckPerms.of(player));
        } catch (RuntimeException notLoaded) {
            return List.of();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // LuckPerms loaded the user before the join, so the groups are right here.
        plugin.connection().send(Frames.join(entry(player)));
        plugin.refreshStatus(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sent.remove(id);
        plugin.cache().forget(id);
        plugin.connection().send(Frames.quit(id));
    }

    private void groupsMayHaveChanged(UUID player) {
        if (!debouncing.add(player)) {
            return;
        }
        plugin.tasks().runAsyncLater(DEBOUNCE_TICKS, () -> {
            debouncing.remove(player);
            if (Bukkit.getPlayer(player) == null) {
                return;
            }
            List<String> now = groups(player);
            List<String> before = sent.put(player, now);
            if (!now.equals(before)) {
                plugin.connection().send(Frames.groups(player, now));
            }
        });
    }
}
