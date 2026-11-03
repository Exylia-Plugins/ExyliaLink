package net.exylia.link.requirement;

import com.google.gson.JsonElement;
import net.exylia.link.LinkCore;
import net.exylia.link.link.LinkCache;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the network's {@link Requirements} on the players here: a warning with a clickable
 * {@code /link} once a player is known to need it, a reminder every few minutes until they link,
 * and in restrict mode the listed commands refused. Re-checked after {@code link.status} on join,
 * on {@code link.updated}, on a group change, when the requirements change and when the network
 * state changes. Keeps the last requirements and statuses it knew while the socket is down, and
 * then never blocks. A backend stays silent while a proxy of the network enforces them
 * ({@link Requirements#enforcedHere}).
 */
public final class LinkRequirements {

    public static final String BYPASS = "exylialink.requirements.bypass";

    private final LinkCore plugin;
    private volatile Requirements current = Requirements.NONE;
    /** Players already warned, and when each is reminded next (0: no reminders). */
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();

    public LinkRequirements(@NotNull LinkCore plugin) {
        this.plugin = plugin;
    }

    /** {@code welcome.requirements} or a {@code requirements.updated} frame; everyone is checked again. */
    public void update(@Nullable JsonElement json) {
        Requirements next = Requirements.of(json);
        if (next.equals(current)) {
            return;
        }
        current = next;
        recheckAll();
    }

    /** Forgets every warning and decides again for everyone online. */
    public void recheckAll() {
        warned.clear();
        plugin.platform().online().forEach(player -> check(player.uuid()));
    }

    /** From any thread: decides on the plugin's own thread. */
    public void check(@NotNull UUID id) {
        plugin.scheduler().async(() -> evaluate(id));
    }

    private boolean enforced() {
        return current.active() && Requirements.enforcedHere(plugin.proxy(), plugin.proxyOnNetwork());
    }

    private void evaluate(UUID id) {
        Optional<Sender> player = plugin.platform().player(id);
        if (player.isEmpty() || !enforced() || !mustLink(player.get())) {
            warned.remove(id);
            return;
        }
        long period = 60_000L * plugin.settings().reminderMinutes();
        if (warned.putIfAbsent(id, period > 0 ? System.currentTimeMillis() + period : 0L) != null) {
            return;
        }
        Requirements requirements = current;
        plugin.say(player.get(), requirements.mode() == Requirements.Mode.RESTRICT
                ? "requirements.warning-restricted" : "requirements.warning", reason(requirements));
    }

    /** Every second: the reminders that are due. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : List.copyOf(warned.entrySet())) {
            if (entry.getValue() == 0L || entry.getValue() > now) {
                continue;
            }
            Optional<Sender> player = plugin.platform().player(entry.getKey());
            if (player.isEmpty() || !enforced() || !mustLink(player.get())) {
                warned.remove(entry.getKey());
                continue;
            }
            long period = 60_000L * plugin.settings().reminderMinutes();
            warned.put(entry.getKey(), period > 0 ? now + period : 0L);
            plugin.say(player.get(), "requirements.reminder", reason(current));
        }
    }

    public void forget(@NotNull UUID id) {
        warned.remove(id);
    }

    public void close() {
        warned.clear();
    }

    private boolean mustLink(Sender player) {
        UUID id = player.uuid();
        return id != null && current.mustLink(plugin.presence().known(id), linked(id), player.hasPermission(BYPASS));
    }

    private @Nullable Boolean linked(UUID id) {
        LinkCache.Status status = plugin.cache().get(id);
        return status == null ? null : status.linked();
    }

    private Values reason(Requirements requirements) {
        return requirements.message() == null
                ? Values.of("reason", plugin.messages().text("requirements.reason"))
                : Values.of().putText("reason", requirements.message());
    }

    /**
     * A command a player typed, from the platform's command event: {@code true} refuses it (the
     * player has been told). Cheap for the commands that are not restricted at all.
     */
    public boolean blocks(@NotNull Sender player, @NotNull String commandLine) {
        Requirements requirements = current;
        if (!requirements.restricts(commandLine) || !enforced() || player.uuid() == null) {
            return false;
        }
        UUID id = player.uuid();
        if (requirements.blocks(commandLine, plugin.presence().known(id), linked(id), player.hasPermission(BYPASS),
                plugin.connection().welcomed())) {
            plugin.say(player, "requirements.blocked", reason(requirements));
            return true;
        }
        return false;
    }
}
