package net.exylia.link.bungee;

import net.exylia.link.LinkCommands;
import net.exylia.link.LinkCore;
import net.exylia.link.platform.Platform;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.BungeeChat;
import net.exylia.link.text.Text;
import net.exylia.link.text.Values;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * BungeeCord and Waterfall: the network's proxy. It sees every player and every command first, so
 * it is the one that talks to players about requirements while it is connected; presence carries
 * the backend each player is on, and rewards that run on the proxy are its console commands. Text
 * is BungeeCord's own chat components.
 */
public final class LinkBungee extends Plugin implements Platform, Listener {

    private LinkCore core;

    @Override
    public void onEnable() {
        core = new LinkCore(this);
        try {
            core.enable(null);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Could not start: the plugin folder cannot be written.", failure);
            core.disable();
            core = null;
            return;
        }
        for (String root : LinkCommands.ROOTS) {
            getProxy().getPluginManager().registerCommand(this, new Root(root));
        }
        getProxy().getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PostLoginEvent event) {
        core.joined(event.getPlayer().getUniqueId(), event.getPlayer().getName(), null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServer(ServerConnectedEvent event) {
        core.switched(event.getPlayer().getUniqueId(), event.getServer().getInfo().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerDisconnectEvent event) {
        core.quit(event.getPlayer().getUniqueId());
    }

    /** Every command a player types reaches the proxy first, also those it forwards to the backend. */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(ChatEvent event) {
        if (!event.isCancelled() && event.isCommand() && event.getSender() instanceof ProxiedPlayer player
                && core.blocksCommand(new PlayerSender(player), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    private final class Root extends Command implements TabExecutor {
        private final String root;

        Root(String root) {
            super(root);
            this.root = root;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            Sender who = sender(sender);
            if (!who.hasPermission(LinkCommands.permission(root))) {
                core.say(who, "errors.no-permission", Values.of());
                return;
            }
            core.commands().execute(who, root, args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            Sender who = sender(sender);
            return who.hasPermission(LinkCommands.permission(root)) ? core.commands().complete(who, root, args) : List.of();
        }
    }

    private Sender sender(CommandSender sender) {
        return sender instanceof ProxiedPlayer player ? new PlayerSender(player) : new ConsoleSender(sender);
    }

    // ---------------------------------------------------------------- platform

    @Override
    public @NotNull String kind() {
        return "proxy";
    }

    @Override
    public @NotNull String platform() {
        return "bungee";
    }

    @Override
    public @NotNull String version() {
        return getProxy().getVersion();
    }

    @Override
    public @NotNull Path dataFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public void log(@NotNull Level level, @NotNull String message) {
        getLogger().log(level, message);
    }

    @Override
    public @NotNull Collection<Online> online() {
        List<Online> players = new ArrayList<>();
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            players.add(new Online(player.getUniqueId(), player.getName(),
                    player.getServer() == null ? null : player.getServer().getInfo().getName()));
        }
        return players;
    }

    @Override
    public @NotNull Optional<Sender> player(@NotNull UUID uuid) {
        return Optional.ofNullable(getProxy().getPlayer(uuid)).map(PlayerSender::new);
    }

    @Override
    public @NotNull Sender console() {
        return new ConsoleSender(getProxy().getConsole());
    }

    @Override
    public @NotNull CompletableFuture<Optional<Online>> resolve(@NotNull String name) {
        return CompletableFuture.completedFuture(Optional.ofNullable(getProxy().getPlayer(name))
                .map(player -> new Online(player.getUniqueId(), player.getName(), null)));
    }

    /** A proxy has no main thread: the plugin's own. */
    @Override
    public void global(@NotNull Runnable task) {
        core.scheduler().async(task);
    }

    @Override
    public boolean dispatch(@NotNull String command) {
        return getProxy().getPluginManager().dispatchCommand(getProxy().getConsole(), command);
    }

    @Override
    public boolean luckPerms() {
        return getProxy().getPluginManager().getPlugin("LuckPerms") != null;
    }

    @Override
    public @NotNull Object handle() {
        return this;
    }

    // ----------------------------------------------------------------- senders

    /** BungeeCord grants nothing by default: /link is for everyone, the rest needs a grant. */
    private record PlayerSender(ProxiedPlayer player) implements Sender {
        @Override
        public UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public @NotNull String name() {
            return player.getName();
        }

        @Override
        public boolean hasPermission(@NotNull String permission) {
            return permission.equals(LinkCore.LINK) || player.hasPermission(permission);
        }

        @Override
        public void send(@NotNull Text.Line line) {
            player.sendMessage(BungeeChat.components(line.spans()));
        }
    }

    private record ConsoleSender(CommandSender sender) implements Sender {
        @Override
        public UUID uuid() {
            return null;
        }

        @Override
        public @NotNull String name() {
            return sender.getName();
        }

        @Override
        public boolean hasPermission(@NotNull String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void send(@NotNull Text.Line line) {
            sender.sendMessage(BungeeChat.components(line.spans()));
        }
    }
}
