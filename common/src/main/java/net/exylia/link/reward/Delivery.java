package net.exylia.link.reward;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.exylia.link.gateway.Frames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a {@code reward.deliver} frame asks for, and what to do with it. Pure logic, unit tested;
 * {@link Rewards} does the running.
 */
public final class Delivery {

    public static final int MAX_COMMANDS = 20;
    public static final int MAX_COMMAND_LENGTH = 1_000;

    /** What happens to a grant. */
    public enum Outcome {
        /** Run the commands, record the grant, ack {@code DELIVERED}. */
        RUN,
        /** Already recorded: ack {@code DELIVERED} without running anything. */
        DUPLICATE,
        /** Past {@code expiresAt}: ack {@code FAILED expired}, record nothing. */
        EXPIRED,
        /** Needs the player here and they are not: ack {@code FAILED player_offline}, record nothing. */
        OFFLINE
    }

    public record Grant(@NotNull String grantId, @NotNull String kind, @NotNull UUID uuid, @NotNull String name,
                        @NotNull List<String> commands, boolean requireOnline, @Nullable Long expiresAt) {

        /** The commands with {@code {player}} replaced: the only placeholder the gateway leaves to the plugin. */
        public List<String> expanded(String playerName) {
            return commands.stream().map(command -> command.replace("{player}", playerName)).toList();
        }
    }

    /** Thrown for a frame that does not match the protocol; acked {@code FAILED internal}. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private Delivery() {
    }

    /**
     * The order matters: a duplicate is {@code DELIVERED} even past its expiry or with the player
     * gone, because its commands already ran.
     */
    public static Outcome decide(boolean recorded, @Nullable Long expiresAt, long now, boolean requireOnline, boolean online) {
        if (recorded) {
            return Outcome.DUPLICATE;
        }
        if (expiresAt != null && now > expiresAt) {
            return Outcome.EXPIRED;
        }
        if (requireOnline && !online) {
            return Outcome.OFFLINE;
        }
        return Outcome.RUN;
    }

    public static Grant parse(JsonObject frame) throws Invalid {
        String grantId = Frames.string(frame, "grantId");
        if (grantId.isEmpty() || grantId.length() > 64) {
            throw new Invalid("grantId must be 1-64 characters");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(Frames.string(frame, "uuid"));
        } catch (IllegalArgumentException malformed) {
            throw new Invalid("uuid is not a UUID");
        }
        String name = Frames.string(frame, "name");
        if (name.isEmpty()) {
            throw new Invalid("name is missing");
        }
        JsonElement list = frame.get("commands");
        if (list == null || !list.isJsonArray()) {
            throw new Invalid("commands is not a list");
        }
        List<String> commands = new ArrayList<>();
        for (JsonElement command : list.getAsJsonArray()) {
            if (!command.isJsonPrimitive()) {
                throw new Invalid("commands holds something that is not text");
            }
            String text = command.getAsString().strip();
            if (text.startsWith("/")) {
                text = text.substring(1);
            }
            if (text.isEmpty() || text.length() > MAX_COMMAND_LENGTH) {
                throw new Invalid("a command is empty or longer than " + MAX_COMMAND_LENGTH + " characters");
            }
            commands.add(text);
        }
        if (commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw new Invalid("commands must hold 1-" + MAX_COMMANDS + " entries");
        }
        String kind = Frames.string(frame, "kind");
        return new Grant(grantId, kind.isEmpty() ? "GRANT" : kind, uuid, name, List.copyOf(commands),
                Frames.bool(frame, "requireOnline"), Frames.longOrNull(frame, "expiresAt"));
    }
}
