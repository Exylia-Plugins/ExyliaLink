package net.exylia.link.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.exylia.link.LinkCommands;
import net.exylia.link.LinkCore;
import net.exylia.link.platform.Platform;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.Text;
import net.exylia.link.text.Values;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Velocity 3: the network's proxy. It sees every player and every command first, so it is the one
 * that talks to players about requirements (warnings, reminders, blocking) while it is connected;
 * presence carries the backend each player is on, and rewards that run on the proxy are its
 * console commands. Text is Velocity's own Adventure.
 */
public final class LinkVelocity implements Platform {

    private final ProxyServer server;
    private final Logger logger;
    private final Path folder;
    private LinkCore core;

    @Inject
    public LinkVelocity(ProxyServer server, Logger logger, @DataDirectory Path folder) {
        this.server = server;
        this.logger = logger;
        this.folder = folder;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        core = new LinkCore(this);
        try {
            core.enable(null);
        } catch (Exception failure) {
            logger.error("Could not start: the plugin folder cannot be written.", failure);
            core.disable();
            core = null;
            return;
        }
        for (String root : LinkCommands.ROOTS) {
            server.getCommandManager().register(server.getCommandManager().metaBuilder(root).plugin(this).build(), new Root(root));
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.disable();
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLogin(PostLoginEvent event) {
        if (core != null) {
            core.joined(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), null);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServer(ServerConnectedEvent event) {
        if (core != null) {
            core.switched(event.getPlayer().getUniqueId(), event.getServer().getServerInfo().getName());
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        // A refused duplicate login leaves the player who is really online in place.
        if (core != null && event.getLoginStatus() != DisconnectEvent.LoginStatus.CONFLICTING_LOGIN) {
            core.quit(event.getPlayer().getUniqueId());
        }
    }

    /** Every command a player types reaches the proxy first, also those it forwards to the backend. */
    @Subscribe(order = PostOrder.EARLY)
    public void onCommand(CommandExecuteEvent event) {
        if (core != null && event.getResult().isAllowed() && event.getCommandSource() instanceof Player player
                && core.blocksCommand(new PlayerSender(player), "/" + event.getCommand())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
        }
    }

    private final class Root implements SimpleCommand {
        private final String root;

        Root(String root) {
            this.root = root;
        }

        @Override
        public void execute(Invocation invocation) {
            Sender who = sender(invocation.source());
            if (!who.hasPermission(LinkCommands.permission(root))) {
                core.say(who, "errors.no-permission", Values.of());
                return;
            }
            core.commands().execute(who, root, invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            // Velocity leaves out the empty argument being typed after a space.
            if (args.length == 0) {
                args = new String[]{""};
            }
            return core.commands().complete(sender(invocation.source()), root, args);
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return sender(invocation.source()).hasPermission(LinkCommands.permission(root));
        }
    }

    private Sender sender(CommandSource source) {
        return source instanceof Player player ? new PlayerSender(player) : new SourceSender(source);
    }

    // ---------------------------------------------------------------- platform

    @Override
    public @NotNull String kind() {
        return "proxy";
    }

    @Override
    public @NotNull String platform() {
        return "velocity";
    }

    @Override
    public @NotNull String version() {
        return server.getVersion().getVersion();
    }

    @Override
    public @NotNull Path dataFolder() {
        return folder;
    }

    @Override
    public void log(@NotNull Level level, @NotNull String message) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            logger.error(message);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            logger.warn(message);
        } else {
            logger.info(message);
        }
    }

    @Override
    public @NotNull Collection<Online> online() {
        List<Online> players = new ArrayList<>();
        for (Player player : server.getAllPlayers()) {
            players.add(new Online(player.getUniqueId(), player.getUsername(),
                    player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null)));
        }
        return players;
    }

    @Override
    public @NotNull Optional<Sender> player(@NotNull UUID uuid) {
        return server.getPlayer(uuid).map(PlayerSender::new);
    }

    @Override
    public @NotNull Sender console() {
        return new SourceSender(server.getConsoleCommandSource());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Online>> resolve(@NotNull String name) {
        return CompletableFuture.completedFuture(server.getPlayer(name)
                .map(player -> new Online(player.getUniqueId(), player.getUsername(), null)));
    }

    /** A proxy has no main thread: the plugin's own. */
    @Override
    public void global(@NotNull Runnable task) {
        core.scheduler().async(task);
    }

    @Override
    public boolean dispatch(@NotNull String command) {
        return server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command).join();
    }

    @Override
    public boolean luckPerms() {
        return server.getPluginManager().isLoaded("luckperms");
    }

    @Override
    public @NotNull Object handle() {
        return this;
    }

    // ----------------------------------------------------------------- senders

    static Component component(List<Text.Span> spans) {
        TextComponent.Builder line = Component.text();
        for (Text.Span span : spans) {
            TextComponent.Builder part = Component.text().content(span.text())
                    .color(span.color() == null ? null : TextColor.fromHexString("#" + span.color()))
                    .decoration(TextDecoration.BOLD, span.has(Text.BOLD))
                    .decoration(TextDecoration.ITALIC, span.has(Text.ITALIC))
                    .decoration(TextDecoration.UNDERLINED, span.has(Text.UNDERLINED))
                    .decoration(TextDecoration.STRIKETHROUGH, span.has(Text.STRIKETHROUGH))
                    .decoration(TextDecoration.OBFUSCATED, span.has(Text.OBFUSCATED));
            if (span.click() != null) {
                try {
                    part.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.valueOf(span.click().action().toUpperCase(Locale.ROOT)),
                            span.click().value()));
                } catch (IllegalArgumentException unknownAction) {
                    // Shown without the click rather than not at all.
                }
            }
            if (span.hover() != null) {
                part.hoverEvent(HoverEvent.showText(component(span.hover())));
            }
            line.append(part.build());
        }
        return line.build();
    }

    /** Players read link codes and warnings everywhere; only an explicit deny takes /link away. */
    private record PlayerSender(Player player) implements Sender {
        @Override
        public UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public @NotNull String name() {
            return player.getUsername();
        }

        @Override
        public boolean hasPermission(@NotNull String permission) {
            return permission.equals(LinkCore.LINK) ? player.getPermissionValue(permission) != Tristate.FALSE
                    : player.hasPermission(permission);
        }

        @Override
        public void send(@NotNull Text.Line line) {
            player.sendMessage(component(line.spans()));
        }
    }

    private record SourceSender(CommandSource source) implements Sender {
        @Override
        public UUID uuid() {
            return null;
        }

        @Override
        public @NotNull String name() {
            return "Console";
        }

        @Override
        public boolean hasPermission(@NotNull String permission) {
            return source.getPermissionValue(permission) != Tristate.FALSE;
        }

        @Override
        public void send(@NotNull Text.Line line) {
            source.sendMessage(component(line.spans()));
        }
    }
}
