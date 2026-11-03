package net.exylia.exyliaLink.gateway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every frame the plugin sends, and the readers for the ones it receives, as protocol 1 spells them
 * (Exylia-Link {@code docs/protocol.md}, {@code packages/core/src/protocol.ts}). Field names here are
 * the protocol's. Pure JSON: no Bukkit, so the shapes are unit tested.
 */
public final class Frames {

    public static final int PROTOCOL = 1;
    public static final int MAX_PLAYERS = 5_000;
    public static final int MAX_GROUPS = 64;
    public static final int MAX_PAIRS = 1_000;

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A player as presence carries them; never an address. */
    public record OnlinePlayer(@NotNull UUID uuid, @NotNull String name, @NotNull List<String> groups) {
    }

    /** A Minecraft account and the Discord account DiscordSRV had for it. */
    public record Pair(@NotNull String uuid, @NotNull String discordId) {
    }

    private Frames() {
    }

    public static String hello(String pluginVersion, String serverId, UUID instanceId, String platform,
                               String minecraftVersion, List<OnlinePlayer> online) {
        Map<String, Object> frame = frame("hello");
        frame.put("protocol", PROTOCOL);
        frame.put("pluginVersion", clip(pluginVersion, 32));
        frame.put("serverId", clip(serverId, 64));
        frame.put("instanceId", instanceId.toString());
        frame.put("platform", clip(platform, 64));
        frame.put("minecraftVersion", clip(minecraftVersion, 32));
        frame.put("online", players(online));
        return GSON.toJson(frame);
    }

    public static String join(OnlinePlayer player) {
        Map<String, Object> frame = frame("player.join");
        frame.putAll(player(player));
        return GSON.toJson(frame);
    }

    public static String quit(UUID uuid) {
        Map<String, Object> frame = frame("player.quit");
        frame.put("uuid", uuid.toString());
        return GSON.toJson(frame);
    }

    public static String groups(UUID uuid, List<String> groups) {
        Map<String, Object> frame = frame("player.groups");
        frame.put("uuid", uuid.toString());
        frame.put("groups", groups(groups));
        return GSON.toJson(frame);
    }

    public static String snapshot(List<OnlinePlayer> online) {
        Map<String, Object> frame = frame("presence.snapshot");
        frame.put("players", players(online));
        return GSON.toJson(frame);
    }

    public static String request(String id, String type, Map<String, Object> args) {
        Map<String, Object> frame = frame("request");
        frame.put("id", id);
        frame.put("type", type);
        frame.put("args", args);
        return GSON.toJson(frame);
    }

    public static String delivered(String grantId) {
        Map<String, Object> frame = frame("reward.ack");
        frame.put("grantId", grantId);
        frame.put("status", "DELIVERED");
        return GSON.toJson(frame);
    }

    public static String failed(String grantId, String code, String message) {
        Map<String, Object> frame = frame("reward.ack");
        frame.put("grantId", grantId);
        frame.put("status", "FAILED");
        frame.put("error", Map.of("code", clip(code, 64), "message", clip(message, 1_000)));
        return GSON.toJson(frame);
    }

    // ------------------------------------------------------------- reading

    public static JsonObject parse(String text) {
        return GSON.fromJson(text, JsonObject.class);
    }

    public static @NotNull String string(JsonObject json, String key) {
        String value = stringOrNull(json, key);
        return value == null ? "" : value;
    }

    public static @Nullable String stringOrNull(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    public static @Nullable Long longOrNull(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        try {
            return value == null || value.isJsonNull() ? null : value.getAsLong();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    public static boolean bool(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    /** A {@code reply} with {@code ok: false}, as the exception its future fails with. */
    public static RequestException error(JsonObject reply) {
        JsonObject error = reply.has("error") && reply.get("error").isJsonObject() ? reply.getAsJsonObject("error") : new JsonObject();
        String code = stringOrNull(error, "code");
        return new RequestException(code == null ? "internal" : code, string(error, "message"),
                longOrNull(error, "retryAfter"), longOrNull(error, "until"));
    }

    // ------------------------------------------------------------- helpers

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }

    public static String json(Object value) {
        return GSON.toJson(value);
    }

    private static Map<String, Object> frame(String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("t", type);
        return frame;
    }

    private static List<Map<String, Object>> players(List<OnlinePlayer> online) {
        List<Map<String, Object>> list = new ArrayList<>(Math.min(online.size(), MAX_PLAYERS));
        for (OnlinePlayer player : online) {
            if (list.size() == MAX_PLAYERS) {
                break;
            }
            list.add(player(player));
        }
        return list;
    }

    private static Map<String, Object> player(OnlinePlayer player) {
        return map("uuid", player.uuid().toString(), "name", clip(player.name(), 32), "groups", groups(player.groups()));
    }

    /** At most 64 groups of at most 64 characters, blanks dropped, order kept. */
    public static List<String> clip(List<String> groups) {
        return groups(groups);
    }

    static List<String> groups(List<String> groups) {
        List<String> clipped = new ArrayList<>();
        for (String group : groups) {
            if (group != null && !group.isBlank() && clipped.size() < MAX_GROUPS) {
                clipped.add(clip(group, 64));
            }
        }
        return clipped;
    }

    public static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
