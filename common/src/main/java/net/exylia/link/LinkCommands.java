package net.exylia.link;

import net.exylia.link.gateway.Connection;
import net.exylia.link.gateway.Frames;
import net.exylia.link.gateway.GatewayHttp;
import net.exylia.link.importer.LinkImport;
import net.exylia.link.link.LinkCache;
import net.exylia.link.platform.Platform;
import net.exylia.link.platform.Sender;
import net.exylia.link.store.NetworkStore;
import net.exylia.link.text.Values;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /link}, {@code /link <code>}, {@code /link status}, {@code /unlink} and {@code /linkadmin},
 * the same on every platform: each one only hands over who ran it and the arguments. Destructive
 * commands are confirmed by running them again with {@code confirm} within 30 seconds (the chat
 * line offers it as a click).
 */
public final class LinkCommands {

    public static final List<String> ROOTS = List.of("link", "unlink", "linkadmin");
    private static final String DEFAULT_PAGE = "https://link.exylia.net";
    private static final long CONFIRM_MILLIS = 30_000L;
    /** Who staff unlinking from the console is, for the activity log. */
    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("connect", "status", "disconnect", "unlink", "import", "reload");

    private final LinkCore plugin;
    private final NetworkStore store;
    private final GatewayHttp http;
    private final LinkImport importer;
    private final AtomicBoolean connecting = new AtomicBoolean();
    /** Confirmations waiting, by action and who asked ({@code unlink:<uuid>}), with their deadline. */
    private final Map<String, Long> confirming = new ConcurrentHashMap<>();

    LinkCommands(LinkCore plugin, NetworkStore store, GatewayHttp http, LinkImport importer) {
        this.plugin = plugin;
        this.store = store;
        this.http = http;
        this.importer = importer;
    }

    /** The permission a root needs, for platforms that check it before the command runs. */
    public static @NotNull String permission(@NotNull String root) {
        return root.equals("linkadmin") ? LinkCore.ADMIN : LinkCore.LINK;
    }

    public void execute(@NotNull Sender sender, @NotNull String root, @NotNull String[] args) {
        switch (root.toLowerCase(Locale.ROOT)) {
            case "link" -> link(sender, args);
            case "unlink" -> unlink(sender, args.length > 0 && args[0].equalsIgnoreCase("confirm"));
            case "linkadmin" -> admin(sender, args);
            default -> {
            }
        }
    }

    public @NotNull List<String> complete(@NotNull Sender sender, @NotNull String root, @NotNull String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (root.equalsIgnoreCase("link") && args.length <= 1) {
            options.add("status");
        } else if (root.equalsIgnoreCase("linkadmin") && sender.hasPermission(LinkCore.ADMIN)) {
            if (args.length <= 1) {
                options.addAll(ADMIN_SUBCOMMANDS);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("unlink")) {
                plugin.platform().online().forEach(player -> options.add(player.name()));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
                options.add("discordsrv");
            }
        }
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }

    void forget(UUID player) {
        confirming.keySet().removeIf(key -> key.endsWith(":" + player));
    }

    private boolean confirmed(String key) {
        Long until = confirming.remove(key);
        return until != null && until >= System.currentTimeMillis();
    }

    private static String who(Sender sender) {
        return sender.uuid() == null ? "console" : sender.uuid().toString();
    }

    // ------------------------------------------------------------------- /link

    private void link(Sender sender, String[] args) {
        UUID id = sender.uuid();
        if (id == null) {
            plugin.say(sender, "errors.players-only", Values.of());
        } else if (args.length == 0) {
            issue(sender, id);
        } else if (args[0].equalsIgnoreCase("status")) {
            status(sender, id);
        } else {
            redeem(sender, id, args[0]);
        }
    }

    /** Game to Discord: a code the player runs in Discord or enters on the link page. */
    private void issue(Sender player, UUID id) {
        plugin.request("code.issue", "uuid", id.toString(), "name", player.name()).whenComplete((data, failure) -> {
            if (failure != null) {
                plugin.fail(player, failure);
                return;
            }
            String code = data.get("code").getAsString();
            long seconds = Math.max(1, (data.get("expiresAt").getAsLong() - System.currentTimeMillis()) / 1000);
            String page = plugin.connection().linkPageUrl();
            plugin.say(player, "link.code", Values.of("code", code)
                    .put("url", page == null || page.isBlank() ? DEFAULT_PAGE : page)
                    .put("time", LinkCore.time(seconds)));
        });
    }

    /** Discord to game: a code issued by {@code /link} in Discord. */
    private void redeem(Sender player, UUID id, String code) {
        plugin.request("code.redeem", "uuid", id.toString(), "name", player.name(), "code", code.length() > 16 ? code.substring(0, 16) : code)
                .whenComplete((data, failure) -> {
                    if (failure != null) {
                        plugin.fail(player, failure);
                        return;
                    }
                    LinkCache.Status status = LinkCache.Status.of(data, true);
                    // The link.updated push says the same: whichever arrives first tells the player.
                    if (plugin.cache().apply(id, status)) {
                        plugin.say(player, "link.redeemed", Values.of().putText("discord", LinkCore.discordLabel(status)));
                    }
                    plugin.requirements().check(id);
                });
    }

    private void status(Sender player, UUID id) {
        LinkCache.Status known = plugin.cache().get(id);
        if (known != null) {
            show(player, known);
            return;
        }
        plugin.request("link.status", "uuid", id.toString()).whenComplete((data, failure) -> {
            if (failure != null) {
                plugin.fail(player, failure);
                return;
            }
            LinkCache.Status status = LinkCache.Status.of(data, false);
            plugin.cache().apply(id, status);
            show(player, status);
        });
    }

    private void show(Sender player, LinkCache.Status status) {
        plugin.say(player, status.linked() ? "link.status-linked" : "link.status-unlinked",
                Values.of().putText("discord", LinkCore.discordLabel(status)).putText("discord_id", status.discordId()));
    }

    // ----------------------------------------------------------------- /unlink

    private void unlink(Sender player, boolean confirm) {
        UUID id = player.uuid();
        if (id == null) {
            plugin.say(player, "errors.players-only", Values.of());
            return;
        }
        LinkCache.Status status = plugin.cache().get(id);
        if (status != null && !status.linked()) {
            plugin.say(player, "link.not-linked", Values.of());
            return;
        }
        if (!confirm || !confirmed("unlink:" + id)) {
            confirming.put("unlink:" + id, System.currentTimeMillis() + CONFIRM_MILLIS);
            plugin.say(player, "link.unlink-confirm", Values.of().putText("discord", status == null ? "Discord" : LinkCore.discordLabel(status)));
            return;
        }
        plugin.request("link.remove", "uuid", id.toString()).whenComplete((data, failure) -> {
            if (failure != null) {
                plugin.fail(player, failure);
                return;
            }
            if (!data.has("removed") || !data.get("removed").getAsBoolean()) {
                plugin.cache().apply(id, LinkCache.Status.UNLINKED);
                plugin.say(player, "link.not-linked", Values.of());
            } else if (plugin.cache().apply(id, LinkCache.Status.UNLINKED)) {
                plugin.say(player, "link.unlink-done", Values.of());
            }
            plugin.requirements().check(id);
        });
    }

    // -------------------------------------------------------------- /linkadmin

    private void admin(Sender sender, String[] args) {
        if (!sender.hasPermission(LinkCore.ADMIN)) {
            plugin.say(sender, "errors.no-permission", Values.of());
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "connect" -> connect(sender);
            case "status" -> status(sender);
            case "disconnect" -> disconnect(sender, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
            case "unlink" -> {
                if (args.length < 2) {
                    plugin.say(sender, "admin.usage", Values.of());
                } else {
                    unlinkOther(sender, args[1]);
                }
            }
            case "import" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("discordsrv")) {
                    importer.start(sender);
                } else {
                    plugin.say(sender, "admin.usage", Values.of());
                }
            }
            case "reload" -> plugin.reload(sender);
            default -> plugin.say(sender, "admin.usage", Values.of());
        }
    }

    /**
     * Device code: the owner approves on the web, the plugin polls for the secret. Each server and
     * proxy collects its own; linking this one again replaces only its own secret.
     */
    private void connect(Sender sender) {
        if (!connecting.compareAndSet(false, true)) {
            plugin.say(sender, "admin.connect-waiting", Values.of());
            return;
        }
        plugin.scheduler().async(() -> {
            try {
                GatewayHttp.Response started = http.post("/v1/link/start", Frames.map(
                        "instanceId", plugin.connection().instanceId().toString(),
                        "serverId", Frames.clip(plugin.serverId(), 64),
                        "pluginVersion", plugin.pluginVersion(),
                        "protocol", Frames.PROTOCOL), null);
                if (started.status() != 200 || started.string("pollToken") == null) {
                    connecting.set(false);
                    plugin.say(sender, "admin.connect-failed", Values.of());
                    return;
                }
                long expiresIn = started.number("expiresIn", 600);
                plugin.say(sender, "admin.connect-code", Values.of().putText("code", started.string("code"))
                        .put("url", started.string("url")).put("time", LinkCore.time(expiresIn)));
                poll(sender, started.string("pollToken"), Math.max(2, started.number("interval", 5)),
                        System.currentTimeMillis() + expiresIn * 1000);
            } catch (Exception failure) {
                connecting.set(false);
                plugin.warn("Could not start linking: " + failure.getMessage());
                plugin.say(sender, "admin.connect-failed", Values.of());
            }
        });
    }

    private void poll(Sender sender, String pollToken, long interval, long deadline) {
        plugin.scheduler().later(interval * 1000, () -> {
            if (System.currentTimeMillis() > deadline) {
                connecting.set(false);
                plugin.say(sender, "admin.connect-expired", Values.of());
                return;
            }
            try {
                GatewayHttp.Response answer = http.post("/v1/link/poll", Map.of("pollToken", pollToken), null);
                switch (answer.status()) {
                    case 200 -> {
                        NetworkStore.Network network = new NetworkStore.Network(answer.string("networkSecret"),
                                answer.string("workspaceId"), answer.string("workspaceName"), System.currentTimeMillis());
                        store.save(network);
                        connecting.set(false);
                        plugin.connection().relink();
                        plugin.say(sender, "admin.connect-done", Values.of().putText("network", network.workspaceName()));
                    }
                    case 410 -> {
                        connecting.set(false);
                        plugin.say(sender, "admin.connect-expired", Values.of());
                    }
                    case 429 -> poll(sender, pollToken, Math.max(interval, answer.retryAfterSeconds()), deadline);
                    default -> poll(sender, pollToken, interval, deadline);
                }
            } catch (Exception transientFailure) {
                poll(sender, pollToken, interval, deadline);
            }
        });
    }

    private void status(Sender sender) {
        Connection connection = plugin.connection();
        String none = plugin.messages().text("admin.none");
        String state = connection.outdated() || connection.state() == Connection.State.STOPPED ? plugin.messages().text("admin.state-stopped")
                : connection.welcomed() ? plugin.messages().text("admin.state-connected")
                : connection.network() == null ? plugin.messages().text("admin.state-not-linked")
                : plugin.messages().text("admin.state-connecting");
        Boolean proxy = plugin.proxyOnNetwork();
        String role = plugin.proxy() ? plugin.messages().text("admin.role-proxy")
                : Boolean.TRUE.equals(proxy) ? plugin.messages().text("admin.role-backend-behind-proxy")
                : plugin.messages().text("admin.role-backend");
        String network = connection.workspaceName();
        long latency = connection.latencyMillis();
        plugin.say(sender, "admin.status", Values.of("state", state).put("role", role)
                .putText("network", network == null ? none : network)
                .putText("server", plugin.serverId())
                .put("latency", latency < 0 ? none : latency + " ms")
                .put("rewards", plugin.rewards().pending())
                .putText("error", connection.lastError().isBlank() ? none : connection.lastError()));
    }

    private void disconnect(Sender sender, boolean confirm) {
        if (plugin.connection().network() == null) {
            plugin.say(sender, "admin.not-connected", Values.of());
            return;
        }
        String key = "disconnect:" + who(sender);
        if (!confirm || !confirmed(key)) {
            confirming.put(key, System.currentTimeMillis() + CONFIRM_MILLIS);
            plugin.say(sender, "admin.disconnect-confirm", Values.of());
            return;
        }
        plugin.scheduler().async(() -> {
            try {
                store.delete();
            } catch (Exception failure) {
                plugin.warn("Could not delete " + store.file() + ": " + failure.getMessage());
                return;
            }
            plugin.connection().relink();
            plugin.say(sender, "admin.disconnected", Values.of());
        });
    }

    private void unlinkOther(Sender sender, String target) {
        UUID actorId = sender.uuid() == null ? CONSOLE : sender.uuid();
        String actorName = sender.uuid() == null ? "Console" : sender.name();
        resolve(target).whenComplete((found, lookupFailure) -> {
            if (lookupFailure != null || found.isEmpty()) {
                plugin.say(sender, "admin.player-not-found", Values.of().putText("player", target));
                return;
            }
            UUID id = found.get().uuid();
            String name = found.get().name();
            plugin.request("link.remove", "uuid", id.toString(), "actor", Frames.map("uuid", actorId.toString(), "name", actorName))
                    .whenComplete((data, failure) -> {
                        if (failure != null) {
                            plugin.fail(sender, failure);
                            return;
                        }
                        boolean removed = data.has("removed") && data.get("removed").getAsBoolean();
                        if (removed) {
                            // The push to the player's server says the same; the cache keeps it to one message.
                            plugin.linkUpdated(Frames.parse(Frames.json(Frames.map("uuid", id.toString(), "linked", false))));
                        }
                        plugin.say(sender, removed ? "admin.unlink-done" : "admin.unlink-none", Values.of().putText("player", name));
                    });
        });
    }

    /** A UUID as typed, or a name the platform knows. */
    private CompletableFuture<Optional<Platform.Online>> resolve(String target) {
        try {
            UUID id = UUID.fromString(target);
            return CompletableFuture.completedFuture(Optional.of(new Platform.Online(id, target, null)));
        } catch (IllegalArgumentException notAUuid) {
            return plugin.platform().resolve(target);
        }
    }
}
