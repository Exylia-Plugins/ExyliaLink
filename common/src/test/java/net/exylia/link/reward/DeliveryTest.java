package net.exylia.link.reward;

import com.google.gson.JsonObject;
import net.exylia.link.gateway.Frames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.exylia.link.reward.Delivery.Outcome.DUPLICATE;
import static net.exylia.link.reward.Delivery.Outcome.EXPIRED;
import static net.exylia.link.reward.Delivery.Outcome.OFFLINE;
import static net.exylia.link.reward.Delivery.Outcome.RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryTest {

    private static final String FRAME = "{\"t\":\"reward.deliver\",\"grantId\":\"rg_1\",\"kind\":\"GRANT\","
            + "\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"name\":\"Notch\","
            + "\"commands\":[\"lp user {player} parent addtemp booster 30d\",\"/give {player} diamond 5\"],"
            + "\"requireOnline\":true,\"expiresAt\":1796342400000}";

    @Test
    void aRecordedGrantIsNeverRunAgainWhateverElseHolds() {
        assertEquals(DUPLICATE, Delivery.decide(true, null, 0, false, true));
        assertEquals(DUPLICATE, Delivery.decide(true, 10L, 20, true, false));
    }

    @Test
    void expiryComesBeforeTheOnlineCheck() {
        assertEquals(EXPIRED, Delivery.decide(false, 10L, 11, true, false));
        assertEquals(RUN, Delivery.decide(false, 10L, 10, false, false));
    }

    @Test
    void requireOnline() {
        assertEquals(OFFLINE, Delivery.decide(false, null, 0, true, false));
        assertEquals(RUN, Delivery.decide(false, null, 0, true, true));
        assertEquals(RUN, Delivery.decide(false, null, 0, false, false));
    }

    @Test
    void parsesTheFrameAndExpandsOnlyThePlayer() throws Exception {
        Delivery.Grant grant = Delivery.parse(Frames.parse(FRAME));
        assertEquals("rg_1", grant.grantId());
        assertTrue(grant.requireOnline());
        assertEquals(1796342400000L, grant.expiresAt());
        assertEquals(List.of("lp user Jeb parent addtemp booster 30d", "give Jeb diamond 5"), grant.expanded("Jeb"));
    }

    @Test
    void refusesFramesOutsideTheProtocol() {
        JsonObject noCommands = Frames.parse(FRAME);
        noCommands.add("commands", new com.google.gson.JsonArray());
        assertThrows(Delivery.Invalid.class, () -> Delivery.parse(noCommands));
        JsonObject badUuid = Frames.parse(FRAME);
        badUuid.addProperty("uuid", "nope");
        assertThrows(Delivery.Invalid.class, () -> Delivery.parse(badUuid));
        JsonObject noExpiry = Frames.parse(FRAME);
        noExpiry.remove("expiresAt");
        assertNull(assertDoesNotThrow(noExpiry).expiresAt());
    }

    private static Delivery.Grant assertDoesNotThrow(JsonObject frame) {
        try {
            return Delivery.parse(frame);
        } catch (Delivery.Invalid invalid) {
            throw new AssertionError(invalid);
        }
    }
}
