package net.exylia.link;

import com.google.gson.JsonObject;
import net.exylia.link.api.ExyliaLinkApi;
import net.exylia.link.config.Settings;
import net.exylia.link.gateway.Connection;
import net.exylia.link.gateway.Frames;
import net.exylia.link.gateway.GatewayHttp;
import net.exylia.link.gateway.RequestException;
import net.exylia.link.importer.LinkImport;
import net.exylia.link.link.LinkCache;
import net.exylia.link.link.Presence;
import net.exylia.link.platform.Platform;
import net.exylia.link.platform.Scheduler;
import net.exylia.link.platform.Sender;
import net.exylia.link.requirement.LinkRequirements;
import net.exylia.link.reward.Rewards;
import net.exylia.link.store.GrantJournal;
import net.exylia.link.store.NetworkStore;
import net.exylia.link.text.Messages;
import net.exylia.link.text.Text;
import net.exylia.link.text.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Exylia Link on any platform: links Minecraft accounts to Discord through the Link gateway.
 *
 * <p>{@code /linkadmin connect} links the network once (device code) and stores the secret in
 * {@code network.dat}; a server or proxy holding that file registers and keeps one socket to the
 * gateway ({@link Connection}). Presence, link codes, link pushes, rewards and the DiscordSRV
 * import all travel on that socket. Exylia-Link {@code docs/protocol.md} is the contract. Each
 * platform module adapts its events, commands and threads to this class and nothing else.
 */
public final class LinkCore {

    public static final String ADMIN = "exylialink.admin";
    public static final String LINK = "exylialink.link";
    private static final long COMPACT_EVERY_MILLIS = 24L * 60 * 60 * 1000;

    private final Platform platform;
    private final Scheduler scheduler;
    private volatile Settings settings;
    private volatile Messages messages = Messages.bundled();
    private NetworkStore store;
    private GrantJournal journal;
    private GatewayHttp http;
    private Connection connection;
    private LinkCache cache;
    private Rewards rewards;
    private Presence presence;
    private LinkRequirements requirements;
    private LinkImport importer;
    private LinkCommands commands;
    /** {@code network.state}: whether a proxy of the network is connected; {@code null} unknown. */
    private volatile Boolean proxyOnNetwork;

    public LinkCore(@NotNull Platform platform) {
        this.platform = platform;
        this.scheduler = new Scheduler(failure -> platform.log(Level.WARNING, "A background task failed: " + failure));
    }

    /**
     * @param discordSrv where {@code /linkadmin import discordsrv} reads from; {@code null} on a proxy
     * @throws IOException when the plugin folder cannot be written: the plugin should not enable
     */
    public void enable(@Nullable Callable<LinkImport.Read> discordSrv) throws IOException {
        settings = Settings.load(platform.dataFolder());
        loadMessages();
        store = new NetworkStore(platform.dataFolder().resolve("network.dat"));
        journal = new GrantJournal(platform.dataFolder().resolve("grants.log"));
        http = new GatewayHttp(() -> settings.gatewayUrl(), "ExyliaLink/" + pluginVersion());
        connection = new Connection(this, store, http);
        cache = new LinkCache(connection::welcomed);
        rewards = new Rewards(this, journal);
        requirements = new LinkRequirements(this);
        presence = new Presence(this);
        importer = new LinkImport(this, discordSrv);
        commands = new LinkCommands(this, store, http, importer);
        ExyliaLinkApi.Holder.set(cache);

        scheduler.every(1_000, 1_000, () -> {
            connection.tick();
            requirements.tick();
        });
        scheduler.every(COMPACT_EVERY_MILLIS, COMPACT_EVERY_MILLIS, () -> {
            try {
                journal.compact();
            } catch (IOException failure) {
                warn("Could not compact grants.log: " + failure.getMessage());
            }
        });
        info("ExyliaLink " + pluginVersion() + " enabled on " + platform.platform() + " " + platform.version()
                + " as " + platform.kind() + " '" + serverId() + "'.");
    }

    public void disable() {
        ExyliaLinkApi.Holder.set(null);
        if (connection != null) connection.stop();
        if (presence != null) presence.close();
        if (requirements != null) requirements.close();
        scheduler.shutdown();
        if (journal != null) {
            try {
                journal.close();
            } catch (IOException ignored) {
                // Every line was already forced to disk.
            }
        }
    }

    private void loadMessages() {
        try {
            messages = Messages.load(platform.dataFolder(), settings.language());
        } catch (IOException | RuntimeException unreadable) {
            warn("Could not read lang/" + settings.language() + "/messages.yml, using the bundled messages: " + unreadable.getMessage());
            messages = Messages.bundled();
        }
    }

    /** {@code /linkadmin reload}: config.yml and the messages. */
    public void reload(@NotNull Sender sender) {
        scheduler.async(() -> {
            try {
                settings = Settings.load(platform.dataFolder());
                loadMessages();
                say(sender, "admin.reloaded", Values.of());
            } catch (IOException | RuntimeException failure) {
                say(sender, "admin.reload-failed", Values.of().putText("error", String.valueOf(failure.getMessage())));
            }
        });
    }

    // ----------------------------------------------------------- platform events

    /** A player joined this server or proxy. */
    public void joined(@NotNull UUID uuid, @NotNull String name, @Nullable String server) {
        presence.joined(uuid, name, server);
        refreshStatus(uuid);
    }

    public void quit(@NotNull UUID uuid) {
        presence.quit(uuid);
        cache.forget(uuid);
        requirements.forget(uuid);
        commands.forget(uuid);
    }

    /** On a proxy: the player connected to another backend. */
    public void switched(@NotNull UUID uuid, @NotNull String server) {
        presence.switched(uuid, server);
    }

    /** A player typed a command: {@code true} refuses it (requirements, restrict mode). */
    public boolean blocksCommand(@NotNull Sender player, @NotNull String commandLine) {
        return requirements.blocks(player, commandLine);
    }

    public @NotNull LinkCommands commands() {
        return commands;
    }

    // ------------------------------------------------------------- frames in

    /** {@code welcome}: statuses may have changed while the socket was down. */
    public void welcomed() {
        platform.online().forEach(player -> refreshStatus(player.uuid()));
    }

    /** {@code link.status} for one online player, into the cache. Never from a placeholder. */
    public void refreshStatus(@NotNull UUID player) {
        connection.request("link.status", Frames.map("uuid", player.toString())).whenComplete((data, failure) -> {
            if (failure != null) {
                debug("link.status for " + player + " failed: " + RequestException.of(failure).code());
            } else if (platform.player(player).isPresent()) {
                cache.apply(player, LinkCache.Status.of(data, false));
                requirements.check(player);
            }
        });
    }

    /**
     * {@code link.updated}: refresh the cache and tell the player, once, whatever said it first. A
     * backend says nothing while a proxy of the network talks to the player.
     */
    public void linkUpdated(@NotNull JsonObject frame) {
        UUID id;
        try {
            id = UUID.fromString(Frames.string(frame, "uuid"));
        } catch (IllegalArgumentException malformed) {
            return;
        }
        Optional<Sender> player = platform.player(id);
        if (player.isEmpty()) {
            return;
        }
        LinkCache.Status status = LinkCache.Status.of(frame, false);
        if (cache.apply(id, status) && talksToPlayers()) {
            say(player.get(), status.linked() ? "link.linked" : "link.unlinked", Values.of().putText("discord", discordLabel(status)));
        }
        requirements.check(id);
    }

    /**
     * {@code welcome.network} or {@code network.state}; {@code null} when unknown (an older
     * gateway, or the socket closed). Requirements are decided again when it really changes.
     */
    public void networkState(@Nullable Boolean proxy) {
        Boolean before = proxyOnNetwork;
        proxyOnNetwork = proxy;
        if (proxy != null && !proxy.equals(before)) {
            debug("Network state: " + (proxy ? "a proxy enforces requirements" : "no proxy connected"));
            requirements.recheckAll();
        }
    }

    public void secretRevoked() {
        tellAdmins("admin.secret-revoked");
    }

    public void protocolRefused() {
        tellAdmins("admin.protocol-refused");
    }

    private void tellAdmins(String key) {
        for (Platform.Online online : platform.online()) {
            platform.player(online.uuid()).filter(player -> player.hasPermission(ADMIN))
                    .ifPresent(player -> say(player, key, Values.of()));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Whether this server messages players about their link (a backend under a proxy does not). */
    public boolean talksToPlayers() {
        return proxy() || !Boolean.TRUE.equals(proxyOnNetwork);
    }

    public boolean proxy() {
        return "proxy".equals(platform.kind());
    }

    public @Nullable Boolean proxyOnNetwork() {
        return proxyOnNetwork;
    }

    public static String discordLabel(LinkCache.Status status) {
        return status.discordName() != null ? status.discordName() : status.discordId() == null ? "Discord" : status.discordId();
    }

    public void say(@Nullable Sender to, @NotNull String key, @NotNull Values values) {
        if (to == null) {
            return;
        }
        for (Text.Line line : messages.lines(key, values)) {
            to.send(line);
        }
    }

    /** One line per request error code, in the player's words. */
    public void fail(@NotNull Sender to, @NotNull Throwable failure) {
        RequestException error = RequestException.of(failure);
        Values values = Values.of().putText("error", error.code());
        String key = switch (error.code()) {
            case RequestException.OFFLINE -> "errors.offline";
            case "rate_limited" -> {
                values.put("time", time(error.retryAfter() == null ? 5 : error.retryAfter()));
                yield "errors.rate-limited";
            }
            case "cooldown" -> {
                long seconds = error.until() == null ? 0 : Math.max(1, (error.until() - System.currentTimeMillis()) / 1000);
                values.put("time", time(seconds));
                yield "errors.cooldown";
            }
            case "already_linked" -> "errors.already-linked";
            case "uuid_taken" -> "errors.uuid-taken";
            case "discord_taken" -> "errors.discord-taken";
            case "code_invalid" -> "errors.code-invalid";
            case "self_unlink_disabled" -> "errors.self-unlink-disabled";
            default -> "errors.internal";
        };
        say(to, key, values);
    }

    /** The two largest units that say something: {@code 3d 4h}, {@code 59m 50s}, {@code 45s}. */
    public static String time(long seconds) {
        long[] units = {31_536_000L, 2_592_000L, 604_800L, 86_400L, 3_600L, 60L, 1L};
        String[] names = {"y", "mo", "w", "d", "h", "m", "s"};
        long left = Math.max(0, seconds);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < units.length && shown < 2; i++) {
            long count = left / units[i];
            if (count > 0 || (i == units.length - 1 && shown == 0)) {
                out.append(shown == 0 ? "" : " ").append(count).append(names[i]);
                left -= count * units[i];
                shown++;
            } else if (shown > 0) {
                break;
            }
        }
        return out.toString();
    }

    public @NotNull String serverId() {
        return settings.serverId();
    }

    public @NotNull String pluginVersion() {
        return Frames.clip(BuildConstants.VERSION, 32);
    }

    public @NotNull String minecraftVersion() {
        return Frames.clip(platform.version(), 32);
    }

    public CompletableFuture<JsonObject> request(String type, Object... args) {
        return connection.request(type, Frames.map(args));
    }

    public void info(String message) {
        platform.log(Level.INFO, message);
    }

    public void warn(String message) {
        platform.log(Level.WARNING, message);
    }

    public void error(String message) {
        platform.log(Level.SEVERE, message);
    }

    public void debug(String message) {
        Settings current = settings;
        if (current != null && current.debug()) {
            platform.log(Level.INFO, "[debug] " + message);
        }
    }

    public Platform platform() { return platform; }
    public Scheduler scheduler() { return scheduler; }
    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public Connection connection() { return connection; }
    public LinkCache cache() { return cache; }
    public Rewards rewards() { return rewards; }
    public Presence presence() { return presence; }
    public LinkRequirements requirements() { return requirements; }
}
