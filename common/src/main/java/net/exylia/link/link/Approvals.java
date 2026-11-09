package net.exylia.link.link;

import com.google.gson.JsonObject;
import net.exylia.link.LinkCore;
import net.exylia.link.api.ExyliaLinkApi.ApprovalDecision;
import net.exylia.link.gateway.Frames;
import net.exylia.link.gateway.RequestException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login approvals answered in Discord (Exylia-Link {@code docs/protocol.md}, Login approvals): one
 * pending request per player, completed by the gateway's {@code approval.decided}, by a cancel
 * (the player left, a newer request) or by the clock. Nothing here fails open: a decision that
 * never arrives is {@link ApprovalDecision#EXPIRED}, and the caller alone decides what a login
 * does with that.
 */
public final class Approvals {

    /** How long past the gateway's {@code expiresAt} a decision may still arrive before the clock ends it. */
    private static final long GRACE_MILLIS = 5_000;
    /** A request the gateway never answered (socket down) ends after this; the gateway's own TTL is 2 minutes. */
    private static final long UNANSWERED_MILLIS = 130_000;

    private static final class Pending {
        final UUID player;
        final CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        volatile @Nullable String id;
        volatile long deadline;

        Pending(UUID player, long deadline) {
            this.player = player;
            this.deadline = deadline;
        }
    }

    private final LinkCore plugin;
    private final Map<UUID, Pending> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, Pending> byId = new ConcurrentHashMap<>();

    public Approvals(@NotNull LinkCore plugin) {
        this.plugin = plugin;
    }

    public @NotNull CompletableFuture<ApprovalDecision> request(@NotNull UUID player, @NotNull String name,
                                                                @Nullable String address, @Nullable String context) {
        cancel(player);
        Pending pending = new Pending(player, System.currentTimeMillis() + UNANSWERED_MILLIS);
        byPlayer.put(player, pending);
        Map<String, Object> args = Frames.map("uuid", player.toString(), "name", Frames.clip(name, 32));
        if (address != null && !address.isBlank()) args.put("address", Frames.clip(address, 64));
        if (context != null && !context.isBlank()) args.put("context", Frames.clip(context, 64));
        plugin.connection().request("approval.request", args).whenComplete((data, failure) -> {
            if (failure != null) {
                String code = RequestException.of(failure).code();
                plugin.debug("approval.request for " + player + " failed: " + code);
                finish(pending, "not_linked".equals(code) ? ApprovalDecision.NOT_LINKED : ApprovalDecision.UNAVAILABLE);
                return;
            }
            String id = Frames.stringOrNull(data, "approvalId");
            Long expiresAt = Frames.longOrNull(data, "expiresAt");
            if (id == null) {
                finish(pending, ApprovalDecision.UNAVAILABLE);
                return;
            }
            if (pending.future.isDone()) {
                // Cancelled while the request was in flight: the card must not stay open in Discord.
                sendCancel(id);
                return;
            }
            pending.id = id;
            if (expiresAt != null) pending.deadline = expiresAt + GRACE_MILLIS;
            byId.put(id, pending);
        });
        return pending.future;
    }

    /** {@code approval.decided} from the gateway. */
    public void decided(@NotNull JsonObject frame) {
        Pending pending = byId.get(Frames.string(frame, "approvalId"));
        if (pending == null) {
            return;
        }
        ApprovalDecision decision;
        try {
            decision = ApprovalDecision.valueOf(Frames.string(frame, "decision").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            decision = ApprovalDecision.UNAVAILABLE;
        }
        finish(pending, decision);
    }

    /** The player's pending approval, if any, ends {@code CANCELLED}; the gateway closes the card. */
    public void cancel(@NotNull UUID player) {
        Pending pending = byPlayer.get(player);
        if (pending != null && finish(pending, ApprovalDecision.CANCELLED) && pending.id != null) {
            sendCancel(pending.id);
        }
    }

    /** Every second: decisions that never came. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Pending pending : byPlayer.values()) {
            if (pending.deadline <= now) {
                finish(pending, ApprovalDecision.EXPIRED);
            }
        }
    }

    /** On disable: nobody waits on a plugin that is gone. */
    public void close() {
        for (Pending pending : byPlayer.values()) {
            finish(pending, ApprovalDecision.UNAVAILABLE);
        }
    }

    /** Completes once and forgets; {@code false} when another outcome got there first. */
    private boolean finish(Pending pending, ApprovalDecision decision) {
        if (!pending.future.complete(decision)) {
            return false;
        }
        byPlayer.remove(pending.player, pending);
        if (pending.id != null) byId.remove(pending.id, pending);
        return true;
    }

    private void sendCancel(String id) {
        plugin.connection().request("approval.cancel", Frames.map("approvalId", id))
                .whenComplete((data, failure) -> {
                    if (failure != null) plugin.debug("approval.cancel " + id + " failed: " + RequestException.of(failure).code());
                });
    }
}
