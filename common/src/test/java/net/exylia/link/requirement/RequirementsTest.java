package net.exylia.link.requirement;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementsTest {

    private static final Requirements RESTRICT = Requirements.of(JsonParser.parseString(
            "{\"t\":\"requirements.updated\",\"groups\":[\"Admin\",\"mod\"],\"mode\":\"restrict\","
                    + "\"restrictedCommands\":[\"staff\",\"vanish\",\"link\"],\"message\":\" Staff must link \",\"future\":1}"));
    private static final List<String> STAFF = List.of("ADMIN", "default");

    @Test
    void readsTheFrameLenientlyAndOldGatewaysAsOff() {
        assertEquals(Requirements.Mode.RESTRICT, RESTRICT.mode());
        assertEquals("Staff must link", RESTRICT.message());
        assertTrue(RESTRICT.groups().contains("admin"));
        assertEquals(Requirements.NONE, Requirements.of(null));
        Requirements odd = Requirements.of(JsonParser.parseString("{\"groups\":\"admin\",\"mode\":\"kick\",\"message\":\"\"}"));
        assertFalse(odd.active());
        assertEquals(Requirements.Mode.WARN, odd.mode());
        assertNull(odd.message());
    }

    @Test
    void onlyAKnownUnlinkedPlayerInARequiredGroupWithoutBypassMustLink() {
        assertTrue(RESTRICT.mustLink(STAFF, false, false));
        assertFalse(RESTRICT.mustLink(STAFF, true, false));
        assertFalse(RESTRICT.mustLink(STAFF, null, false));
        assertFalse(RESTRICT.mustLink(STAFF, false, true));
        assertFalse(RESTRICT.mustLink(List.of("default"), false, false));
        assertFalse(Requirements.NONE.mustLink(STAFF, false, false));
    }

    @Test
    void blocksListedRootsAndTheirNamespacedFormButNeverLink() {
        assertTrue(RESTRICT.blocks("/staff", STAFF, false, false, true));
        assertTrue(RESTRICT.blocks("/StaffPlugin:STAFF mode", STAFF, false, false, true));
        assertTrue(RESTRICT.blocks("/vanish", STAFF, false, false, true));
        assertFalse(RESTRICT.blocks("/link", STAFF, false, false, true));
        assertFalse(RESTRICT.blocks("/exylialink:link ABC123", STAFF, false, false, true));
        assertFalse(RESTRICT.blocks("/staffchat hi", STAFF, false, false, true));
        assertFalse(RESTRICT.blocks("/staff", STAFF, true, false, true));
    }

    @Test
    void aBackendStaysSilentOnlyWhileAProxyOfTheNetworkIsConnected() {
        assertTrue(Requirements.enforcedHere(true, true), "a proxy always enforces");
        assertTrue(Requirements.enforcedHere(true, null));
        assertFalse(Requirements.enforcedHere(false, true), "the proxy talks to the player");
        assertTrue(Requirements.enforcedHere(false, false));
        assertTrue(Requirements.enforcedHere(false, null), "unknown (older gateway, offline) behaves as before");
    }

    @Test
    void labels() {
        assertEquals("msg", Requirements.label("/Essentials:MSG Bob hi"));
        assertEquals("staff", Requirements.label("staff"));
        assertEquals("", Requirements.label(null));
    }

    @Test
    void failsOpenWhileDisconnectedAndNeverBlocksInWarnMode() {
        assertFalse(RESTRICT.blocks("/staff", STAFF, false, false, false));
        Requirements warn = Requirements.of(JsonParser.parseString(
                "{\"groups\":[\"admin\"],\"mode\":\"warn\",\"restrictedCommands\":[\"staff\"]}"));
        assertTrue(warn.mustLink(STAFF, false, false));
        assertFalse(warn.blocks("/staff", STAFF, false, false, true));
    }
}
