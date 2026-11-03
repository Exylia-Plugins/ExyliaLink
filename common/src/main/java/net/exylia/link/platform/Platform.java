package net.exylia.link.platform;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** What the shared code needs from Paper, Spigot, Folia, Velocity or BungeeCord. */
public interface Platform {

    /** A player online here, and the backend they are on when this is a proxy. */
    record Online(@NotNull UUID uuid, @NotNull String name, @Nullable String server) {
    }

    /** {@code backend} or {@code proxy}, as {@code hello.kind}. */
    @NotNull String kind();

    /** {@code paper}, {@code folia}, {@code spigot}, {@code velocity} or {@code bungee}. */
    @NotNull String platform();

    /** The Minecraft version of a server, the proxy's own version on a proxy. */
    @NotNull String version();

    @NotNull Path dataFolder();

    void log(@NotNull Level level, @NotNull String message);

    @NotNull Collection<Online> online();

    @NotNull Optional<Sender> player(@NotNull UUID uuid);

    @NotNull Sender console();

    /** Online here by exact name first, then (where the platform knows them) a player who played here before. */
    @NotNull CompletableFuture<Optional<Online>> resolve(@NotNull String name);

    /**
     * Runs on the thread console commands belong to: the main thread, Folia's global region, or
     * any thread on a proxy.
     */
    void global(@NotNull Runnable task);

    /** Runs one console command, on the {@link #global} thread; {@code false} when it is unknown. */
    boolean dispatch(@NotNull String command);

    /** Whether LuckPerms is installed and enabled here. */
    boolean luckPerms();

    /** The plugin object other APIs (LuckPerms' event bus) know this plugin by. */
    @NotNull Object handle();
}
