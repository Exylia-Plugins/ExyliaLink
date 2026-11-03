package net.exylia.exyliaLink.reward;

import com.google.gson.JsonObject;
import net.exylia.exyliaLink.gateway.Frames;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code reward.deliver}: commands run once per grant id, however often the gateway sends it.
 *
 * <p>Delivery is at-least-once and effects exactly-once: every grant run is recorded in
 * {@code exylialink_grants}, shared by every server of the network, and a recorded grant is acked
 * {@code DELIVERED} again without running. Rows are kept 90 days.
 */
public final class Rewards {

    static final long KEEP_MILLIS = 90L * 24 * 60 * 60 * 1000;
    private static final int PURGE_PAGE = 500;

    @Table(value = "exylialink_grants", data = DataKind.CACHE, cache = false)
    @Index(columns = {"executed_at"})
    public record GrantRow(
            @Id(length = 64) String id,
            @Owner @Indexed @Column UUID player,
            @Column(value = "executed_at") long executedAt) {
    }

    private final Repository<GrantRow> grants;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final Consumer<String> send;
    private final Supplier<String> serverId;
    /** Grants between their frame and their ack on this server: a resend meanwhile is dropped, the first one acks. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public Rewards(@NotNull PluginDatabase database, @NotNull TaskScheduler tasks, @NotNull Debug debug,
                   @NotNull Consumer<String> send, @NotNull Supplier<String> serverId) {
        this.grants = database.repository(GrantRow.class);
        this.tasks = tasks;
        this.debug = debug;
        this.send = send;
        this.serverId = serverId;
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
            debug.warn("A reward.deliver frame was refused: " + invalid.getMessage());
            if (!grantId.isEmpty() && grantId.length() <= 64) {
                send.accept(Frames.failed(grantId, "internal", invalid.getMessage()));
            }
            return;
        }
        if (!inFlight.add(grant.grantId())) {
            return;
        }
        grants.find(grant.grantId()).thenAccept(found -> {
            Player online = Bukkit.getPlayer(grant.uuid());
            switch (Delivery.decide(found.isPresent(), grant.expiresAt(), System.currentTimeMillis(),
                    grant.requireOnline(), online != null)) {
                case DUPLICATE -> done(grant, Frames.delivered(grant.grantId()));
                case EXPIRED -> done(grant, Frames.failed(grant.grantId(), "expired", "Received after it expired"));
                case OFFLINE -> done(grant, Frames.failed(grant.grantId(), "player_offline",
                        grant.name() + " is not online on " + serverId.get()));
                case RUN -> tasks.run(() -> run(grant));
            }
        }).exceptionally(failure -> {
            // No ack: the gateway sends it again in a minute.
            inFlight.remove(grant.grantId());
            debug.warn("Could not read grant " + grant.grantId() + ": " + failure.getMessage());
            return null;
        });
    }

    /** On the global thread (the main thread off Folia), as console, in order; recorded right after. */
    private void run(Delivery.Grant grant) {
        Player online = Bukkit.getPlayer(grant.uuid());
        List<String> commands = grant.expanded(online != null ? online.getName() : grant.name());
        for (String command : commands) {
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    debug.warn("Reward " + grant.grantId() + ": unknown command '" + command + "'");
                }
            } catch (RuntimeException failure) {
                // Another plugin's error: the rest still run, and the grant still counts as run.
                debug.warn("Reward " + grant.grantId() + ": '" + command + "' failed: " + failure);
            }
        }
        grants.save(new GrantRow(grant.grantId(), grant.uuid(), System.currentTimeMillis())).whenComplete((ignored, failure) -> {
            if (failure != null) {
                debug.warn("Reward " + grant.grantId() + " ran but could not be recorded: " + failure.getMessage());
            }
            done(grant, Frames.delivered(grant.grantId()));
        });
    }

    private void done(Delivery.Grant grant, String ack) {
        inFlight.remove(grant.grantId());
        send.accept(ack);
    }

    /** Deletes grants older than 90 days, oldest first, a page at a time. */
    public CompletableFuture<Integer> purge() {
        return purgeFrom(System.currentTimeMillis() - KEEP_MILLIS, 0);
    }

    private CompletableFuture<Integer> purgeFrom(long cutoff, int deleted) {
        return grants.all().orderBy("executed_at").limit(PURGE_PAGE).find().thenCompose(page -> {
            List<GrantRow> old = page.stream().filter(row -> row.executedAt() < cutoff).toList();
            CompletableFuture<?>[] deletes = old.stream().map(row -> grants.delete(row.id())).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(deletes).thenCompose(ignored -> old.size() == PURGE_PAGE
                    ? purgeFrom(cutoff, deleted + old.size())
                    : CompletableFuture.completedFuture(deleted + old.size()));
        });
    }
}
