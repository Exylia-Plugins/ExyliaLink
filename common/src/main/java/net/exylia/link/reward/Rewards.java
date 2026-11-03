package net.exylia.link.reward;

import com.google.gson.JsonObject;
import net.exylia.link.LinkCore;
import net.exylia.link.gateway.Frames;
import net.exylia.link.platform.Platform;
import net.exylia.link.store.GrantJournal;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code reward.deliver}: commands run once per grant id, however often the gateway sends it.
 *
 * <p>Delivery is at-least-once and effects exactly-once: every grant run here is recorded in the
 * {@link GrantJournal} (on disk before the ack), and a recorded grant is acked {@code DELIVERED}
 * again without running. On a proxy the commands are the proxy's console commands: the gateway
 * sends a proxy only the grants of rules that run on the proxy.
 */
public final class Rewards {

    private final LinkCore plugin;
    private final GrantJournal journal;
    /** Grants between their frame and their ack here: a resend meanwhile is dropped, the first one acks. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public Rewards(@NotNull LinkCore plugin, @NotNull GrantJournal journal) {
        this.plugin = plugin;
        this.journal = journal;
    }

    public int pending() {
        return inFlight.size();
    }

    public void deliver(@NotNull JsonObject frame) {
        Delivery.Grant grant;
        try {
            grant = Delivery.parse(frame);
        } catch (Delivery.Invalid invalid) {
            String grantId = Frames.string(frame, "grantId");
            plugin.warn("A reward.deliver frame was refused: " + invalid.getMessage());
            if (!grantId.isEmpty() && grantId.length() <= 64) {
                plugin.connection().send(Frames.failed(grantId, "internal", invalid.getMessage()));
            }
            return;
        }
        if (!inFlight.add(grant.grantId())) {
            return;
        }
        boolean online = plugin.platform().player(grant.uuid()).isPresent();
        switch (Delivery.decide(journal.contains(grant.grantId()), grant.expiresAt(), System.currentTimeMillis(),
                grant.requireOnline(), online)) {
            case DUPLICATE -> done(grant, Frames.delivered(grant.grantId()));
            case EXPIRED -> done(grant, Frames.failed(grant.grantId(), "expired", "Received after it expired"));
            case OFFLINE -> done(grant, Frames.failed(grant.grantId(), "player_offline",
                    grant.name() + " is not online on " + plugin.serverId()));
            case RUN -> plugin.platform().global(() -> run(grant));
        }
    }

    /** On the global thread (the main thread off Folia, any thread on a proxy), as console, in order. */
    private void run(Delivery.Grant grant) {
        Optional<String> current = plugin.platform().online().stream()
                .filter(player -> player.uuid().equals(grant.uuid())).map(Platform.Online::name).findFirst();
        List<String> commands = grant.expanded(current.orElse(grant.name()));
        for (String command : commands) {
            try {
                if (!plugin.platform().dispatch(command)) {
                    plugin.warn("Reward " + grant.grantId() + ": unknown command '" + command + "'");
                }
            } catch (RuntimeException failure) {
                // Another plugin's error: the rest still run, and the grant still counts as run.
                plugin.warn("Reward " + grant.grantId() + ": '" + command + "' failed: " + failure);
            }
        }
        plugin.scheduler().async(() -> {
            try {
                journal.record(grant.grantId());
            } catch (Exception failure) {
                plugin.warn("Reward " + grant.grantId() + " ran but could not be recorded: " + failure.getMessage());
            }
            done(grant, Frames.delivered(grant.grantId()));
        });
    }

    private void done(Delivery.Grant grant, String ack) {
        inFlight.remove(grant.grantId());
        plugin.connection().send(ack);
    }
}
