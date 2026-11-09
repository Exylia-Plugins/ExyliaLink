package net.exylia.link.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Who a player is on Discord, for other plugins on the same server or proxy (Paper, Spigot,
 * Folia, Velocity, BungeeCord). On Bukkit it is also registered in the ServicesManager.
 *
 * <pre>{@code
 * ExyliaLinkApi link = ExyliaLinkApi.get();
 * if (link != null) link.discordId(player.getUniqueId()).ifPresent(id -> ...);
 * }</pre>
 *
 * <p>Answers come from memory: the status of every player online here, asked once on join and
 * kept fresh by the gateway's pushes. A player who is not online here reads as not linked. Safe
 * from any thread. Only {@link #requestLoginApproval} talks to the network, and says so.
 */
public interface ExyliaLinkApi {

    /** The running instance, or {@code null} while ExyliaLink is not enabled. */
    static @Nullable ExyliaLinkApi get() {
        return Holder.instance;
    }

    boolean isLinked(@NotNull UUID player);

    /** The Discord id (a snowflake, as text), when linked. */
    @NotNull Optional<String> discordId(@NotNull UUID player);

    /** The Discord username, when linked and Link knows it. */
    @NotNull Optional<String> discordName(@NotNull UUID player);

    /**
     * Whether the linked Discord account boosts a Discord server of the network, as the Link bot
     * last saw it. {@code false} when not linked. Perks for boosters belong in a Link reward rule
     * ({@code Boost active}), not in code reading this.
     */
    boolean isBoosting(@NotNull UUID player);

    /** Whether this server is connected to Exylia Link right now. */
    boolean isConnected();

    /**
     * Asks the linked Discord account to confirm a login: the Link bot sends them a DM with
     * <i>Yes, it was me</i> and <i>No, block it</i>, and the answer completes the future, within
     * about two minutes. The future never fails: every way it can end is a {@link ApprovalDecision},
     * and only {@link ApprovalDecision#APPROVED} means yes. A new request for the same player
     * cancels their pending one; a player who leaves has theirs cancelled.
     *
     * @param address the player's address as this server sees it, or {@code null}; Link stores and
     *                shows it masked ({@code 203.0.113.•••.•••}), never whole
     * @param context a short label shown on the card (this server's name), or {@code null}
     */
    @NotNull CompletableFuture<ApprovalDecision> requestLoginApproval(@NotNull UUID player, @NotNull String name,
                                                                      @Nullable String address, @Nullable String context);

    /** Ends the player's pending approval, if any: the DM stops offering buttons and the future completes {@code CANCELLED}. */
    void cancelLoginApproval(@NotNull UUID player);

    /** How a login approval ended. Only {@link #APPROVED} lets the login through. */
    enum ApprovalDecision {
        /** The account owner pressed Yes. */
        APPROVED,
        /** They pressed No: refuse the login, and consider locking the account for a while. */
        DENIED,
        /** Nobody answered in time (or the answer never reached this server). */
        EXPIRED,
        /** Cancelled here: a newer request, the player left, or {@link #cancelLoginApproval}. */
        CANCELLED,
        /** The bot could not DM the account (DMs closed, bot blocked). */
        UNDELIVERABLE,
        /** The player has no linked Discord account: use another factor. */
        NOT_LINKED,
        /** Link is not reachable right now (disconnected, rate limited, an error): use another factor. */
        UNAVAILABLE;

        /** The decisions where asking another way makes sense, as opposed to a real answer or a timeout. */
        public boolean fallback() {
            return this == NOT_LINKED || this == UNAVAILABLE || this == UNDELIVERABLE;
        }
    }

    /** Set by the plugin on enable and cleared on disable; not for other plugins. */
    final class Holder {
        static volatile ExyliaLinkApi instance;

        private Holder() {
        }

        public static void set(@Nullable ExyliaLinkApi api) {
            instance = api;
        }
    }
}
