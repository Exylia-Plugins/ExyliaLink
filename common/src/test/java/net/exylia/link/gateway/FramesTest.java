package net.exylia.link.gateway;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramesTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void helloCarriesEveryFieldOfProtocolOne() {
        UUID instance = UUID.randomUUID();
        JsonObject hello = Frames.parse(Frames.hello("backend", "1.0.0", "survival", instance, "paper", "1.21.8",
                List.of(new Frames.OnlinePlayer(NOTCH, "Notch", List.of("vip", "default")))));
        assertEquals("hello", hello.get("t").getAsString());
        assertEquals(1, hello.get("protocol").getAsInt());
        assertEquals("backend", hello.get("kind").getAsString());
        assertEquals("paper", hello.get("platform").getAsString());
        assertEquals("survival", hello.get("serverId").getAsString());
        assertEquals(instance.toString(), hello.get("instanceId").getAsString());
        JsonObject player = hello.getAsJsonArray("online").get(0).getAsJsonObject();
        assertEquals(NOTCH.toString(), player.get("uuid").getAsString());
        assertEquals("vip", player.getAsJsonArray("groups").get(0).getAsString());
        assertEquals(3, player.keySet().size(), "uuid, name and groups only: never an address");
    }

    @Test
    void aProxyReportsTheBackendEachPlayerIsOn() {
        JsonObject hello = Frames.parse(Frames.hello("proxy", "1.0.0", "velocity", UUID.randomUUID(), "velocity", "3.4.0",
                List.of(new Frames.OnlinePlayer(NOTCH, "Notch", List.of(), "survival"),
                        new Frames.OnlinePlayer(UUID.randomUUID(), "Jeb", List.of(), null))));
        assertEquals("proxy", hello.get("kind").getAsString());
        assertEquals("survival", hello.getAsJsonArray("online").get(0).getAsJsonObject().get("server").getAsString());
        assertFalse(hello.getAsJsonArray("online").get(1).getAsJsonObject().has("server"), "omitted before they reach one");
        assertEquals("{\"t\":\"player.server\",\"uuid\":\"" + NOTCH + "\",\"server\":\"lobby\"}", Frames.server(NOTCH, "lobby"));
        JsonObject join = Frames.parse(Frames.join(new Frames.OnlinePlayer(NOTCH, "Notch", List.of("vip"), "lobby")));
        assertEquals("lobby", join.get("server").getAsString());
    }

    @Test
    void groupsAreClippedToTheProtocolLimits() {
        List<String> many = Collections.nCopies(80, "g".repeat(70));
        JsonObject frame = Frames.parse(Frames.groups(NOTCH, many));
        assertEquals("player.groups", frame.get("t").getAsString());
        assertEquals(64, frame.getAsJsonArray("groups").size());
        assertEquals(64, frame.getAsJsonArray("groups").get(0).getAsString().length());
    }

    @Test
    void presenceFrames() {
        assertEquals("{\"t\":\"player.quit\",\"uuid\":\"" + NOTCH + "\"}", Frames.quit(NOTCH));
        JsonObject join = Frames.parse(Frames.join(new Frames.OnlinePlayer(NOTCH, "Notch", List.of())));
        assertEquals("player.join", join.get("t").getAsString());
        assertEquals("Notch", join.get("name").getAsString());
        assertEquals(0, Frames.parse(Frames.snapshot(List.of())).getAsJsonArray("players").size());
    }

    @Test
    void requestFrameNestsArgs() {
        JsonObject request = Frames.parse(Frames.request("r-1", "import.batch", Frames.map("importId", "x",
                "pairs", List.of(new Frames.Pair(NOTCH.toString(), "123456789012345678")), "final", true)));
        assertEquals("request", request.get("t").getAsString());
        assertEquals("r-1", request.get("id").getAsString());
        JsonObject pair = request.getAsJsonObject("args").getAsJsonArray("pairs").get(0).getAsJsonObject();
        assertEquals("123456789012345678", pair.get("discordId").getAsString());
        assertTrue(request.getAsJsonObject("args").get("final").getAsBoolean());
    }

    @Test
    void acks() {
        assertEquals("{\"t\":\"reward.ack\",\"grantId\":\"rg_1\",\"status\":\"DELIVERED\"}", Frames.delivered("rg_1"));
        JsonObject failed = Frames.parse(Frames.failed("rg_1", "player_offline", "Notch is not online on survival"));
        assertEquals("FAILED", failed.get("status").getAsString());
        assertEquals("player_offline", failed.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void replyErrorsBecomeRequestExceptions() {
        RequestException cooldown = Frames.error(Frames.parse(
                "{\"t\":\"reply\",\"id\":\"r-2\",\"ok\":false,\"error\":{\"code\":\"cooldown\",\"message\":\"wait\",\"until\":1793750400000}}"));
        assertEquals("cooldown", cooldown.code());
        assertEquals(1793750400000L, cooldown.until());
        assertNull(cooldown.retryAfter());
        RequestException limited = Frames.error(Frames.parse(
                "{\"t\":\"reply\",\"id\":\"r-3\",\"ok\":false,\"error\":{\"code\":\"rate_limited\",\"message\":\"\",\"retryAfter\":12}}"));
        assertEquals(12L, limited.retryAfter());
        assertEquals("internal", Frames.error(Frames.parse("{\"t\":\"reply\",\"ok\":false}")).code());
    }

    @Test
    void readersTolerateNullsAndMissingKeys() {
        JsonObject frame = Frames.parse("{\"t\":\"link.updated\",\"uuid\":\"x\",\"linked\":false,\"discordId\":null}");
        assertNull(Frames.stringOrNull(frame, "discordId"));
        assertNull(Frames.stringOrNull(frame, "discordName"));
        assertFalse(Frames.bool(frame, "linked"));
        assertEquals(Map.of(), Map.of());
    }
}
