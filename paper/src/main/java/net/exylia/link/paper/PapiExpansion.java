package net.exylia.link.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.exylia.link.BuildConstants;
import net.exylia.link.link.LinkCache;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code %exylialink_linked%}, {@code %exylialink_discord_name%}, {@code %exylialink_discord_id%}:
 * from the online player's cached status, never a network call. Only loaded when PlaceholderAPI is enabled.
 */
final class PapiExpansion extends PlaceholderExpansion {

    private final LinkCache cache;

    PapiExpansion(LinkCache cache) {
        this.cache = cache;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "exylialink";
    }

    @Override
    public @NotNull String getAuthor() {
        return "DiGround";
    }

    @Override
    public @NotNull String getVersion() {
        return BuildConstants.VERSION;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        return switch (params) {
            case "linked" -> String.valueOf(player != null && cache.isLinked(player.getUniqueId()));
            case "discord_name" -> player == null ? "" : cache.discordName(player.getUniqueId()).orElse("");
            case "discord_id" -> player == null ? "" : cache.discordId(player.getUniqueId()).orElse("");
            default -> null;
        };
    }
}
