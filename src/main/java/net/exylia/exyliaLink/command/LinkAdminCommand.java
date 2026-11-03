package net.exylia.exyliaLink.command;

import net.exylia.exyliaLink.ExyliaLink;
import net.exylia.exyliaLink.config.LinkMessages;
import net.exylia.exyliaLink.gateway.Connection;
import net.exylia.exyliaLink.gateway.Frames;
import net.exylia.exyliaLink.gateway.GatewayHttp;
import net.exylia.exyliaLink.importer.DiscordSrvImport;
import net.exylia.exyliaLink.link.LinkCache;
import net.exylia.exyliaLink.link.NetworkStore;
import net.exylia.lib.config.ConfigMenus;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.text.Values;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@code /linkadmin}: connect the network, its status, unlink players, import DiscordSRV, settings. */
@Command("linkadmin")
@CommandPermission(ExyliaLink.ADMIN)
public final class LinkAdminCommand {

    /** Who staff unlinking from the console is, for the activity log. */
    private static final UUID CONSOLE = new UUID(0L, 0L);

    private final ExyliaLink plugin;
    private final NetworkStore store;
    private final GatewayHttp http;
    private final DiscordSrvImport importer;
    private final AtomicBoolean connecting = new AtomicBoolean();

    public LinkAdminCommand(ExyliaLink plugin, NetworkStore store, GatewayHttp http, DiscordSrvImport importer) {
        this.plugin = plugin;
        this.store = store;
        this.http = http;
        this.importer = importer;
    }

    private LinkMessages.Admin lines() {
        return plugin.messages().admin();
    }

    // ---------------------------------------------------------------- connect

    /**
     * Device code: the owner approves on the web, the plugin polls for the secret. Linking a
     * network again simply moves it to the new secret.
     */
    @Subcommand("connect")
    public void connect(CommandSender sender) {
        if (!connecting.compareAndSet(false, true)) {
            plugin.say(sender, lines().connectWaiting(), Values.of());
            return;
        }
        plugin.tasks().runAsync(() -> {
            try {
                GatewayHttp.Response started = http.post("/v1/link/start", Frames.map(
                        "instanceId", plugin.connection().instanceId().toString(),
                        "serverId", Frames.clip(plugin.serverId(), 64),
                        "pluginVersion", plugin.pluginVersion(),
                        "protocol", Frames.PROTOCOL), null);
                if (started.status() != 200 || started.string("pollToken") == null) {
                    connecting.set(false);
                    plugin.say(sender, lines().connectFailed(), Values.of());
                    return;
                }
                long expiresIn = started.number("expiresIn", 600);
                plugin.say(sender, lines().connectCode(), Values.of("code", started.string("code"))
                        .put("url", started.string("url")).put("time", ExyliaLink.time(expiresIn)));
                poll(sender, started.string("pollToken"), Math.max(2, started.number("interval", 5)),
                        System.currentTimeMillis() + expiresIn * 1000);
            } catch (Exception failure) {
                connecting.set(false);
                plugin.debug().warn("Could not start linking: " + failure.getMessage());
                plugin.say(sender, lines().connectFailed(), Values.of());
            }
        });
    }

    private void poll(CommandSender sender, String pollToken, long interval, long deadline) {
        plugin.tasks().runAsyncLater(interval * 20, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                connecting.set(false);
                plugin.say(sender, lines().connectExpired(), Values.of());
                return;
            }
            try {
                GatewayHttp.Response answer = http.post("/v1/link/poll", Map.of("pollToken", pollToken), null);
                switch (answer.status()) {
                    case 200 -> {
                        NetworkStore.Network network = new NetworkStore.Network(answer.string("networkSecret"),
                                answer.string("workspaceId"), answer.string("workspaceName"), System.currentTimeMillis());
                        store.save(network).join();
                        connecting.set(false);
                        plugin.connection().relink();
                        plugin.say(sender, lines().connectDone(), Values.of("network", network.workspaceName()));
                    }
                    case 410 -> {
                        connecting.set(false);
                        plugin.say(sender, lines().connectExpired(), Values.of());
                    }
                    case 429 -> poll(sender, pollToken, Math.max(interval, answer.retryAfterSeconds()), deadline);
                    default -> poll(sender, pollToken, interval, deadline);
                }
            } catch (Exception transientFailure) {
                poll(sender, pollToken, interval, deadline);
            }
        });
    }

    // ----------------------------------------------------------------- status

    @Subcommand("status")
    public void status(CommandSender sender) {
        Connection connection = plugin.connection();
        LinkMessages.Admin lines = lines();
        String state = connection.outdated() || connection.state() == Connection.State.STOPPED ? lines.stateStopped()
                : connection.welcomed() ? lines.stateConnected()
                : connection.network() == null ? lines.stateNotLinked() : lines.stateConnecting();
        String network = connection.workspaceName();
        long latency = connection.latencyMillis();
        plugin.say(sender, lines.status(), Values.of("state", state)
                .putText("network", network == null ? lines.none() : network)
                .putText("server", plugin.serverId())
                .put("latency", latency < 0 ? lines.none() : latency + " ms")
                .put("rewards", plugin.rewards().pending())
                .putText("error", connection.lastError().isBlank() ? lines.none() : connection.lastError()));
    }

    // ------------------------------------------------------------- disconnect

    @Subcommand("disconnect")
    public void disconnect(CommandSender sender) {
        if (plugin.connection().network() == null) {
            plugin.say(sender, lines().notConnected(), Values.of());
            return;
        }
        if (sender instanceof Player player) {
            Inputs.of(plugin).confirm(player, lines().disconnectConfirm()).dangerous()
                    .open(yes -> { if (yes) disconnectNow(sender); });
        } else {
            disconnectNow(sender);
        }
    }

    private void disconnectNow(CommandSender sender) {
        store.delete().whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.debug().warn("Could not delete the network link: " + failure.getMessage());
                return;
            }
            plugin.connection().relink();
            plugin.say(sender, lines().disconnected(), Values.of());
        });
    }

    // ----------------------------------------------------------------- unlink

    @Subcommand("unlink")
    public void unlink(CommandSender sender, @Single String target) {
        UUID actorId = sender instanceof Player player ? player.getUniqueId() : CONSOLE;
        String actorName = sender instanceof Player player ? player.getName() : "Console";
        ExyliaPlayers.resolve(target).whenComplete((found, lookupFailure) -> {
            if (lookupFailure != null || found.isEmpty()) {
                plugin.say(sender, lines().playerNotFound(), Values.of().putText("player", target));
                return;
            }
            UUID id = found.get().id();
            String name = found.get().name();
            plugin.request("link.remove", "uuid", id.toString(), "actor", Frames.map("uuid", actorId.toString(), "name", actorName))
                    .whenComplete((data, failure) -> {
                        if (failure != null) {
                            plugin.fail(sender, failure);
                            return;
                        }
                        boolean removed = data.has("removed") && data.get("removed").getAsBoolean();
                        if (removed && Bukkit.getPlayer(id) != null) {
                            // The push to the player's server says the same; the cache keeps it to one message.
                            plugin.linkUpdated(Frames.parse(Frames.json(Frames.map("uuid", id.toString(), "linked", false))));
                        }
                        plugin.say(sender, removed ? lines().unlinkDone() : lines().unlinkNone(), Values.of().putText("player", name));
                    });
        });
    }

    // ----------------------------------------------------------------- others

    @Subcommand("import discordsrv")
    public void importDiscordSrv(CommandSender sender) {
        importer.start(sender);
    }

    @Subcommand("reload")
    public void reload(CommandSender sender) {
        plugin.reload(sender);
    }

    @Subcommand("settings")
    public void settings(Player player) {
        ConfigMenus.of(plugin).permission(ExyliaLink.ADMIN).open(player);
    }
}
