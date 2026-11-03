package net.exylia.link.platform;

import net.exylia.link.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A player or a console, on whichever platform runs the plugin. Safe from any thread. */
public interface Sender {

    /** {@code null} for a console. */
    @Nullable UUID uuid();

    @NotNull String name();

    boolean hasPermission(@NotNull String permission);

    /** Delivered on the thread that owns the receiver; the sound is played to the player only, where the platform can. */
    void send(@NotNull Text.Line line);
}
