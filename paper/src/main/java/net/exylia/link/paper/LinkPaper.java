package net.exylia.link.paper;

import net.exylia.link.LinkCommands;
import net.exylia.link.LinkCore;
import net.exylia.link.api.ExyliaLinkApi;
import net.exylia.link.platform.Platform;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.BungeeChat;
import net.exylia.link.text.Text;
import net.exylia.link.text.Values;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
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
 * Paper, Spigot and Folia 1.17+: a game server of the network. Presence with LuckPerms groups,
 * rewards run as console on the main thread (Folia: the global region), PlaceholderAPI
 * placeholders, the DiscordSRV import, and requirements unless a proxy of the network enforces them.
 * Only Spigot API is called directly; Paper and Folia extras are reached after a runtime check.
 */
public final class LinkPaper extends JavaPlugin implements Platform, Listener, TabExecutor {

    private LinkCore core;
    private String platform;

    @Override
    public void onEnable() {
        platform = has("io.papermc.paper.threadedregions.RegionizedServer") ? "folia"
                : has("com.destroystokyo.paper.PaperConfig") || has("io.papermc.paper.configuration.Configuration") ? "paper"
                : "spigot";
        core = new LinkCore(this);
        try {
            core.enable(new DiscordSrvReader(getDataFolder().getParentFile()));
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Could not start: the plugin folder cannot be written.", failure);
            core.disable();
            core = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String root : LinkCommands.ROOTS) {
            PluginCommand command = getCommand(root);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(ExyliaLinkApi.class, core.cache(), this, ServicePriority.Normal);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PapiExpansion(core.cache()).register();
        }
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    private static boolean has(String type) {
        try {
            Class.forName(type);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // LuckPerms loaded the user before the join, so the groups are right here.
        core.joined(event.getPlayer().getUniqueId(), event.getPlayer().getName(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        core.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (core.blocksCommand(new PlayerSender(event.getPlayer()), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String root = command.getName();
        Sender who = sender(sender);
        if (!who.hasPermission(LinkCommands.permission(root))) {
            core.say(who, "errors.no-permission", Values.of());
            return true;
        }
        core.commands().execute(who, root, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        Sender who = sender(sender);
        return who.hasPermission(LinkCommands.permission(command.getName()))
                ? core.commands().complete(who, command.getName(), args) : List.of();
    }

    private Sender sender(CommandSender sender) {
        return sender instanceof Player player ? new PlayerSender(player) : new ConsoleSender(sender);
    }

    // ---------------------------------------------------------------- platform

    @Override
    public @NotNull String kind() {
        return "backend";
    }

    @Override
    public @NotNull String platform() {
        return platform;
    }

    @Override
    public @NotNull String version() {
        String bukkit = Bukkit.getBukkitVersion();
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
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
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            players.add(new Online(player.getUniqueId(), player.getName(), null));
        }
        return players;
    }

    @Override
    public @NotNull Optional<Sender> player(@NotNull UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? Optional.empty() : Optional.of(new PlayerSender(player));
    }

    @Override
    public @NotNull Sender console() {
        return new ConsoleSender(Bukkit.getConsoleSender());
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NotNull CompletableFuture<Optional<Online>> resolve(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Online(online.getUniqueId(), online.getName(), null)));
        }
        // May ask Mojang for an unknown name, so never on a server thread.
        return CompletableFuture.supplyAsync(() -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline.hasPlayedBefore()
                    ? Optional.of(new Online(offline.getUniqueId(), offline.getName() == null ? name : offline.getName(), null))
                    : Optional.empty();
        });
    }

    @Override
    public void global(@NotNull Runnable task) {
        Schedulers.run(this, task);
    }

    @Override
    public boolean dispatch(@NotNull String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public boolean luckPerms() {
        return getServer().getPluginManager().isPluginEnabled("LuckPerms");
    }

    @Override
    public @NotNull Object handle() {
        return this;
    }

    // ----------------------------------------------------------------- senders

    private final class PlayerSender implements Sender {
        private final Player player;

        PlayerSender(Player player) {
            this.player = player;
        }

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
            return player.hasPermission(permission);
        }

        @Override
        public void send(@NotNull Text.Line line) {
            Schedulers.runAt(LinkPaper.this, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.spigot().sendMessage(BungeeChat.components(line.spans()));
                if (line.sound() != null) {
                    player.playSound(player.getLocation(), line.sound().key(), line.sound().volume(), line.sound().pitch());
                }
            });
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
            sender.sendMessage(Text.legacy(line.spans()));
        }
    }
}
