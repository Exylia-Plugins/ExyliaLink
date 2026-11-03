package net.exylia.exyliaLink;

import com.google.gson.JsonObject;
import net.exylia.exyliaLink.api.ExyliaLinkApi;
import net.exylia.exyliaLink.command.LinkAdminCommand;
import net.exylia.exyliaLink.command.LinkCommand;
import net.exylia.exyliaLink.config.LinkMessages;
import net.exylia.exyliaLink.config.LinkSettings;
import net.exylia.exyliaLink.gateway.Connection;
import net.exylia.exyliaLink.gateway.Frames;
import net.exylia.exyliaLink.gateway.GatewayHttp;
import net.exylia.exyliaLink.gateway.RequestException;
import net.exylia.exyliaLink.importer.DiscordSrvImport;
import net.exylia.exyliaLink.link.LinkCache;
import net.exylia.exyliaLink.link.NetworkStore;
import net.exylia.exyliaLink.presence.Presence;
import net.exylia.exyliaLink.requirement.LinkRequirements;
import net.exylia.exyliaLink.reward.Rewards;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Prefixes;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Exylia Link in game: links Minecraft accounts to Discord through the Link gateway.
 *
 * <p>{@code /linkadmin connect} links the network once (device code) and stores the secret in the
 * shared database; every server finds it there, registers and keeps one socket to the gateway
 * ({@link Connection}). Presence, link codes, link pushes, rewards and the DiscordSRV import all
 * travel on that socket. Exylia-Link {@code docs/protocol.md} is the contract.
 */
public final class ExyliaLink extends JavaPlugin {

    public static final String ADMIN = "exylialink.admin";
    private static final long PURGE_EVERY_TICKS = 20L * 60 * 60 * 24;

    private TaskScheduler tasks;
    private Debug debug;
    private ConfigFile<LinkSettings> settings;
    private ConfigFile<LinkMessages> messages;
    private PluginMessages lines;
    private Connection connection;
    private LinkCache cache;
    private Rewards rewards;
    private Presence presence;
    private LinkRequirements requirements;
    private Lamp<BukkitCommandActor> lamp;
    private Reloads reloads;

    @Override
    public void onEnable() {
        tasks = Tasks.of(this);
        debug = Debug.of(this);
        settings = Configs.define(this, "config", LinkSettings.class).load();
        messages = Configs.define(this, "messages", LinkMessages.class).translated().load();
        Prefixes.set(this, messages.get().prefix());
        lines = PluginMessages.of(this);

        PluginDatabase database = Databases.of(this);
        NetworkStore store = new NetworkStore(database);
        GatewayHttp http = new GatewayHttp(() -> settings.get().gatewayUrl(), "ExyliaLink/" + pluginVersion());
        connection = new Connection(this, store, http);
        cache = new LinkCache(connection::welcomed);
        rewards = new Rewards(database, tasks, debug, frame -> connection.send(frame), this::serverId);
        presence = new Presence(this);
        getServer().getPluginManager().registerEvents(presence, this);
        requirements = new LinkRequirements(this);
        getServer().getPluginManager().registerEvents(requirements, this);

        Placeholders.group(this, "exylialink")
                .async()
                .add("linked", request -> String.valueOf(request.hasViewer() && cache.isLinked(request.requireViewer().getUniqueId())))
                .add("discord_name", request -> request.hasViewer() ? cache.discordName(request.requireViewer().getUniqueId()).orElse("") : "")
                .add("discord_id", request -> request.hasViewer() ? cache.discordId(request.requireViewer().getUniqueId()).orElse("") : "")
                .register();
        getServer().getServicesManager().register(ExyliaLinkApi.class, cache, this, ServicePriority.Normal);

        lamp = BukkitLamp.builder(this).build();
        lamp.register(new LinkCommand(this), new LinkAdminCommand(this, store, http, new DiscordSrvImport(this)));

        tasks.runAsyncTimer(20L, 20L, connection::tick);
        tasks.runAsyncTimer(20L * 60, PURGE_EVERY_TICKS, () -> rewards.purge().whenComplete((deleted, failure) -> {
            if (failure != null) {
                debug.warn("Could not purge old reward grants: " + failure.getMessage());
            } else if (deleted > 0) {
                debug.log("Purged " + deleted + " reward grants older than 90 days.");
            }
        }));

        reloads = Reloads.of(this).step("configs", () -> {
            Configs.reloadAll(this);
            Prefixes.set(this, messages.get().prefix());
        });
        debug.motd();
    }

    @Override
    public void onDisable() {
        if (connection != null) connection.stop();
        if (presence != null) presence.close();
        if (requirements != null) requirements.close();
        if (lamp != null) lamp.unregisterAllCommands();
        getServer().getServicesManager().unregisterAll(this);
    }

    // ------------------------------------------------------------- frames in

    /** {@code welcome}: statuses may have changed while the socket was down. */
    public void welcomed() {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            refreshStatus(player.getUniqueId());
        }
    }

    /** {@code link.status} for one online player, into the cache. Never from a placeholder. */
    public void refreshStatus(@NotNull UUID player) {
        connection.request("link.status", Frames.map("uuid", player.toString())).whenComplete((data, failure) -> {
            if (failure != null) {
                debug.debug("link.status for " + player + " failed: " + RequestException.of(failure).code());
            } else if (Bukkit.getPlayer(player) != null) {
                cache.apply(player, LinkCache.Status.of(data, false));
                requirements.check(player);
            }
        });
    }

    /** {@code link.updated}: refresh the cache and tell the player, once, whatever said it first. */
    public void linkUpdated(@NotNull JsonObject frame) {
        UUID id;
        try {
            id = UUID.fromString(Frames.string(frame, "uuid"));
        } catch (IllegalArgumentException malformed) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        LinkCache.Status status = LinkCache.Status.of(frame, false);
        if (cache.apply(id, status)) {
            LinkMessages.Link link = messages.get().link();
            say(player, status.linked() ? link.linked() : link.unlinked(), Values.of("discord", discordLabel(status)));
        }
        requirements.check(id);
    }

    public void secretRevoked() {
        tellAdmins(messages.get().admin().secretRevoked());
    }

    public void protocolRefused() {
        tellAdmins(messages.get().admin().protocolRefused());
    }

    private void tellAdmins(String line) {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (player.hasPermission(ADMIN)) {
                say(player, line, Values.of());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    public static String discordLabel(LinkCache.Status status) {
        return status.discordName() != null ? status.discordName() : status.discordId() == null ? "Discord" : status.discordId();
    }

    public void say(@Nullable CommandSender to, @Nullable String line, @NotNull Values values) {
        lines.send(to, line, values);
    }

    public void say(@Nullable CommandSender to, @Nullable List<String> block, @NotNull Values values) {
        lines.send(to, block, values);
    }

    /** One line per request error code, in the player's words. */
    public void fail(@NotNull CommandSender to, @NotNull Throwable failure) {
        RequestException error = RequestException.of(failure);
        LinkMessages.Errors errors = messages.get().errors();
        Values values = Values.of("error", error.code());
        String line = switch (error.code()) {
            case RequestException.OFFLINE -> errors.offline();
            case "rate_limited" -> {
                values.put("time", time(error.retryAfter() == null ? 5 : error.retryAfter()));
                yield errors.rateLimited();
            }
            case "cooldown" -> {
                long seconds = error.until() == null ? 0 : Math.max(1, (error.until() - System.currentTimeMillis()) / 1000);
                values.put("time", time(seconds));
                yield errors.cooldown();
            }
            case "already_linked" -> errors.alreadyLinked();
            case "uuid_taken" -> errors.uuidTaken();
            case "discord_taken" -> errors.discordTaken();
            case "code_invalid" -> errors.codeInvalid();
            case "self_unlink_disabled" -> errors.selfUnlinkDisabled();
            default -> errors.internal();
        };
        say(to, line, values);
    }

    public static String time(long seconds) {
        return TimeFormats.render(seconds, TimeFormats.Style.COMPACT);
    }

    public void reload(CommandSender sender) {
        reloads.run(sender);
    }

    public @NotNull String serverId() {
        String configured = settings.get().serverId();
        return configured == null || configured.isBlank() ? Redis.serverId(this) : configured.trim();
    }

    @SuppressWarnings("UnstableApiUsage")
    public @NotNull String pluginVersion() {
        return Frames.clip(getPluginMeta().getVersion(), 32);
    }

    public @NotNull String platform() {
        return Frames.clip(Bukkit.getName() + " " + Bukkit.getVersion(), 64);
    }

    public @NotNull String minecraftVersion() {
        return Frames.clip(Bukkit.getMinecraftVersion(), 32);
    }

    public CompletableFuture<JsonObject> request(String type, Object... args) {
        return connection.request(type, Frames.map(args));
    }

    public TaskScheduler tasks() { return tasks; }
    public Debug debug() { return debug; }
    public LinkMessages messages() { return messages.get(); }
    public Connection connection() { return connection; }
    public LinkCache cache() { return cache; }
    public Rewards rewards() { return rewards; }
    public Presence presence() { return presence; }
    public LinkRequirements requirements() { return requirements; }
    public LinkSettings settings() { return settings.get(); }
}
