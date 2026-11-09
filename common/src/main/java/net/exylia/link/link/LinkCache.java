package net.exylia.link.link;

import com.google.gson.JsonObject;
import net.exylia.link.api.ExyliaLinkApi;
import net.exylia.link.gateway.Frames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The link status of every player online here: filled by {@code link.status} on join, kept fresh
 * by {@code link.updated} and by this server's own replies, dropped on quit. Placeholders and the
 * API read only this; nothing here asks the network, except the login approvals it hands to
 * {@link Approvals}.
 */
public final class LinkCache implements ExyliaLinkApi {

    public record Status(boolean linked, @Nullable String discordId, @Nullable String discordName, boolean boosting) {
        public static final Status UNLINKED = new Status(false, null, null, false);

        /** From {@code link.status} data, a {@code link.updated} frame or a {@code code.redeem} reply (linked). */
        public static Status of(JsonObject json, boolean linkedByDefault) {
            boolean linked = json.has("linked") ? Frames.bool(json, "linked") : linkedByDefault;
            return linked ? new Status(true, Frames.stringOrNull(json, "discordId"), Frames.stringOrNull(json, "discordName"), Frames.bool(json, "boosting"))
                    : UNLINKED;
        }
    }

    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
    private final BooleanSupplier connected;
    private final Approvals approvals;

    public LinkCache(@NotNull BooleanSupplier connected, @NotNull Approvals approvals) {
        this.connected = connected;
        this.approvals = approvals;
    }

    /**
     * Stores a status and answers whether the player went from linked to unlinked or back (or was
     * not known): a reply and the push that say the same thing message the player once.
     */
    public boolean apply(@NotNull UUID player, @NotNull Status status) {
        Status before = statuses.put(player, status);
        return before == null || before.linked() != status.linked();
    }

    public @Nullable Status get(@NotNull UUID player) {
        return statuses.get(player);
    }

    public void forget(@NotNull UUID player) {
        statuses.remove(player);
    }

    @Override
    public boolean isLinked(@NotNull UUID player) {
        Status status = statuses.get(player);
        return status != null && status.linked();
    }

    @Override
    public @NotNull Optional<String> discordId(@NotNull UUID player) {
        Status status = statuses.get(player);
        return status == null || !status.linked() ? Optional.empty() : Optional.ofNullable(status.discordId());
    }

    @Override
    public @NotNull Optional<String> discordName(@NotNull UUID player) {
        Status status = statuses.get(player);
        return status == null || !status.linked() ? Optional.empty() : Optional.ofNullable(status.discordName());
    }

    @Override
    public boolean isBoosting(@NotNull UUID player) {
        Status status = statuses.get(player);
        return status != null && status.linked() && status.boosting();
    }

    @Override
    public boolean isConnected() {
        return connected.getAsBoolean();
    }

    @Override
    public @NotNull CompletableFuture<ApprovalDecision> requestLoginApproval(@NotNull UUID player, @NotNull String name,
                                                                             @Nullable String address, @Nullable String context) {
        return approvals.request(player, name, address, context);
    }

    @Override
    public void cancelLoginApproval(@NotNull UUID player) {
        approvals.cancel(player);
    }
}
