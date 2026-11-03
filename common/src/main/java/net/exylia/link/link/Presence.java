package net.exylia.link.link;

import net.exylia.link.LinkCore;
import net.exylia.link.gateway.Frames;
import net.exylia.link.platform.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online here and in which LuckPerms groups: {@code player.join}, {@code player.quit},
 * {@code player.groups} (debounced 1 s, sent only when the list changed), {@code player.server}
 * on a proxy, and the full list for {@code hello} and the five-minute {@code presence.snapshot}.
 * Never an address.
 */
public final class Presence {

    private static final long DEBOUNCE_MILLIS = 1_000L;

    private final LinkCore plugin;
    private final LuckPermsGroups luckPerms;
    /** The groups the gateway was last told, per online player. */
    private final Map<UUID, List<String>> sent = new ConcurrentHashMap<>();
    private final Set<UUID> debouncing = ConcurrentHashMap.newKeySet();

    public Presence(@NotNull LinkCore plugin) {
        this.plugin = plugin;
        LuckPermsGroups groups = null;
        if (plugin.platform().luckPerms()) {
            try {
                groups = new LuckPermsGroups();
                groups.listen(plugin.platform().handle(), this::groupsMayHaveChanged);
            } catch (RuntimeException | LinkageError notReady) {
                plugin.warn("LuckPerms is installed but its API is not available: groups are not reported (" + notReady + ").");
                groups = null;
            }
        }
        this.luckPerms = groups;
    }

    public void close() {
        if (luckPerms != null) {
            luckPerms.close();
        }
    }

    public @NotNull List<Frames.OnlinePlayer> online() {
        List<Frames.OnlinePlayer> players = new ArrayList<>();
        for (Platform.Online player : plugin.platform().online()) {
            players.add(entry(player));
        }
        return players;
    }

    private Frames.OnlinePlayer entry(Platform.Online player) {
        List<String> groups = groups(player.uuid());
        sent.put(player.uuid(), groups);
        return new Frames.OnlinePlayer(player.uuid(), player.name(), groups, player.server());
    }

    /** The groups last reported for an online player here, primary first; empty when unknown. */
    public @NotNull List<String> known(@NotNull UUID player) {
        return sent.getOrDefault(player, List.of());
    }

    private List<String> groups(UUID player) {
        try {
            return luckPerms == null ? List.of() : Frames.clip(luckPerms.of(player));
        } catch (RuntimeException notLoaded) {
            return List.of();
        }
    }

    /** After the platform's join (LuckPerms has loaded the user by then, so the groups are right). */
    public void joined(@NotNull UUID uuid, @NotNull String name, @Nullable String server) {
        plugin.connection().send(Frames.join(entry(new Platform.Online(uuid, name, server))));
    }

    public void quit(@NotNull UUID uuid) {
        sent.remove(uuid);
        plugin.connection().send(Frames.quit(uuid));
    }

    /** On a proxy: the player is now on {@code server}. */
    public void switched(@NotNull UUID uuid, @NotNull String server) {
        plugin.connection().send(Frames.server(uuid, server));
    }

    private void groupsMayHaveChanged(UUID player) {
        if (!debouncing.add(player)) {
            return;
        }
        plugin.scheduler().later(DEBOUNCE_MILLIS, () -> {
            debouncing.remove(player);
            if (plugin.platform().player(player).isEmpty()) {
                return;
            }
            List<String> now = groups(player);
            List<String> before = sent.put(player, now);
            if (!now.equals(before)) {
                plugin.connection().send(Frames.groups(player, now));
                plugin.requirements().check(player);
            }
        });
    }
}
