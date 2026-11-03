package net.exylia.link.requirement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Groups that must have a linked account, as {@code welcome.requirements} and
 * {@code requirements.updated} carry them (Exylia-Link {@code docs/protocol.md}, Requirements).
 * Pure: the decisions are unit tested.
 *
 * @param groups             LuckPerms groups, lower case; empty turns the feature off
 * @param restrictedCommands command roots blocked in {@link Mode#RESTRICT} until linked, lower case
 * @param message            the network's own reason, or {@code null} for the plugin's
 */
public record Requirements(@NotNull Set<String> groups, @NotNull Mode mode, @NotNull Set<String> restrictedCommands,
                           @Nullable String message) {

    public enum Mode { WARN, RESTRICT }

    public static final Requirements NONE = new Requirements(Set.of(), Mode.WARN, Set.of(), null);

    /** Leniently: anything missing or malformed reads as off, never as an error. */
    public static @NotNull Requirements of(@Nullable JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return NONE;
        }
        JsonObject object = json.getAsJsonObject();
        Mode mode = "restrict".equals(string(object, "mode")) ? Mode.RESTRICT : Mode.WARN;
        String message = string(object, "message");
        return new Requirements(lowered(object, "groups"), mode, lowered(object, "restrictedCommands"),
                message == null || message.isBlank() ? null : message.strip());
    }

    public boolean active() {
        return !groups.isEmpty();
    }

    /**
     * Whether this server talks to players about requirements at all (warnings, reminders,
     * blocking). A proxy always does. A backend stays silent while the gateway says a proxy of the
     * network is connected ({@code network.state}): the proxy sees every player and every command
     * first. Unknown ({@code null}: an older gateway, or offline) behaves as a network without one.
     */
    public static boolean enforcedHere(boolean proxy, @Nullable Boolean proxyOnNetwork) {
        return proxy || !Boolean.TRUE.equals(proxyOnNetwork);
    }

    /**
     * Whether this player must be told to link: in a required group, known to be unlinked
     * ({@code linked} {@code null} is unknown: never), and without the bypass.
     */
    public boolean mustLink(@NotNull Collection<String> playerGroups, @Nullable Boolean linked, boolean bypass) {
        if (!active() || bypass || !Boolean.FALSE.equals(linked)) {
            return false;
        }
        for (String group : playerGroups) {
            if (groups.contains(group.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Whether this command line is restricted at all: restrict mode, a listed root (plain or {@code plugin:root}), never {@code /link}. */
    public boolean restricts(@NotNull String commandLine) {
        if (mode != Mode.RESTRICT || restrictedCommands.isEmpty()) {
            return false;
        }
        String label = label(commandLine);
        return !label.equals("link") && restrictedCommands.contains(label);
    }

    /**
     * Whether to refuse the command. Fails open: with the gateway unreachable nothing is blocked,
     * whatever the cached status says.
     */
    public boolean blocks(@NotNull String commandLine, @NotNull Collection<String> playerGroups, @Nullable Boolean linked,
                          boolean bypass, boolean connected) {
        return connected && restricts(commandLine) && mustLink(playerGroups, linked, bypass);
    }

    /** The root of a typed command: lower case, without the slash or a {@code plugin:} prefix ({@code /Essentials:MSG Bob} is {@code msg}). */
    public static @NotNull String label(@Nullable String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        String label = space < 0 ? trimmed : trimmed.substring(0, space);
        int colon = label.indexOf(':');
        return (colon < 0 ? label : label.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private static @Nullable String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static Set<String> lowered(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement entry : (JsonArray) value) {
            if (entry.isJsonPrimitive() && !entry.getAsString().isBlank()) {
                out.add(entry.getAsString().strip().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
