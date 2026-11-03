package net.exylia.exyliaLink.api;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Who a player is on Discord, for other plugins. Registered in Bukkit's ServicesManager while
 * ExyliaLink is enabled.
 *
 * <pre>{@code
 * ExyliaLinkApi link = ExyliaLinkApi.get();
 * if (link != null) link.discordId(player.getUniqueId()).ifPresent(id -> ...);
 * }</pre>
 *
 * <p>Answers come from memory: the status of every player online on this server, asked once on
 * join and kept fresh by the gateway's pushes. A player who is not online here reads as not
 * linked. Safe from any thread.
 */
public interface ExyliaLinkApi {

    /** The registered service, or {@code null} while ExyliaLink is not enabled. */
    static @Nullable ExyliaLinkApi get() {
        return Bukkit.getServicesManager().load(ExyliaLinkApi.class);
    }

    boolean isLinked(@NotNull UUID player);

    /** The Discord id (a snowflake, as text), when linked. */
    @NotNull Optional<String> discordId(@NotNull UUID player);

    /** The Discord username, when linked and Link knows it. */
    @NotNull Optional<String> discordName(@NotNull UUID player);

    /** Whether this server is connected to Exylia Link right now. */
    boolean isConnected();
}
