package net.exylia.exyliaLink.command;

import net.exylia.exyliaLink.ExyliaLink;
import net.exylia.exyliaLink.config.LinkMessages;
import net.exylia.exyliaLink.link.LinkCache;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.text.Values;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.UUID;

/** {@code /link}, {@code /link <code>}, {@code /link status} and {@code /unlink}. */
public final class LinkCommand {

    private static final String DEFAULT_PAGE = "https://link.exylia.net";

    private final ExyliaLink plugin;

    public LinkCommand(ExyliaLink plugin) {
        this.plugin = plugin;
    }

    /** Game to Discord: a code the player runs in Discord or enters on the link page. */
    @Command("link")
    @CommandPermission("exylialink.link")
    public void issue(Player player) {
        plugin.request("code.issue", "uuid", player.getUniqueId().toString(), "name", player.getName())
                .whenComplete((data, failure) -> {
                    if (failure != null) {
                        plugin.fail(player, failure);
                        return;
                    }
                    String code = data.get("code").getAsString();
                    long seconds = Math.max(1, (data.get("expiresAt").getAsLong() - System.currentTimeMillis()) / 1000);
                    String page = plugin.connection().linkPageUrl();
                    plugin.say(player, plugin.messages().link().code(), Values.of("code", code)
                            .put("url", page == null || page.isBlank() ? DEFAULT_PAGE : page)
                            .put("time", ExyliaLink.time(seconds)));
                });
    }

    /** Discord to game: a code issued by {@code /link} in Discord. */
    @Command("link")
    @CommandPermission("exylialink.link")
    public void redeem(Player player, @Single String code) {
        UUID id = player.getUniqueId();
        plugin.request("code.redeem", "uuid", id.toString(), "name", player.getName(), "code", code.length() > 16 ? code.substring(0, 16) : code)
                .whenComplete((data, failure) -> {
                    if (failure != null) {
                        plugin.fail(player, failure);
                        return;
                    }
                    LinkCache.Status status = LinkCache.Status.of(data, true);
                    // The link.updated push says the same: whichever arrives first tells the player.
                    if (plugin.cache().apply(id, status)) {
                        plugin.say(player, plugin.messages().link().redeemed(), Values.of("discord", ExyliaLink.discordLabel(status)));
                    }
                });
    }

    @Command("link status")
    @CommandPermission("exylialink.link")
    public void status(Player player) {
        LinkCache.Status known = plugin.cache().get(player.getUniqueId());
        if (known != null) {
            show(player, known);
            return;
        }
        plugin.request("link.status", "uuid", player.getUniqueId().toString()).whenComplete((data, failure) -> {
            if (failure != null) {
                plugin.fail(player, failure);
                return;
            }
            LinkCache.Status status = LinkCache.Status.of(data, false);
            plugin.cache().apply(player.getUniqueId(), status);
            show(player, status);
        });
    }

    private void show(Player player, LinkCache.Status status) {
        LinkMessages.Link link = plugin.messages().link();
        plugin.say(player, status.linked() ? link.statusLinked() : link.statusUnlinked(),
                Values.of("discord", ExyliaLink.discordLabel(status)).put("discord_id", status.discordId()));
    }

    @Command("unlink")
    @CommandPermission("exylialink.link")
    public void unlink(Player player) {
        LinkMessages.Link link = plugin.messages().link();
        LinkCache.Status status = plugin.cache().get(player.getUniqueId());
        if (status != null && !status.linked()) {
            plugin.say(player, link.notLinked(), Values.of());
            return;
        }
        String label = status == null ? "Discord" : ExyliaLink.discordLabel(status);
        Inputs.of(plugin).confirm(player, Values.of("discord", label).apply(link.unlinkConfirm())).dangerous().open(yes -> {
            if (!yes) {
                return;
            }
            UUID id = player.getUniqueId();
            plugin.request("link.remove", "uuid", id.toString()).whenComplete((data, failure) -> {
                if (failure != null) {
                    plugin.fail(player, failure);
                    return;
                }
                if (!data.has("removed") || !data.get("removed").getAsBoolean()) {
                    plugin.cache().apply(id, LinkCache.Status.UNLINKED);
                    plugin.say(player, link.notLinked(), Values.of());
                } else if (plugin.cache().apply(id, LinkCache.Status.UNLINKED)) {
                    plugin.say(player, link.unlinkDone(), Values.of());
                }
            });
        });
    }
}
