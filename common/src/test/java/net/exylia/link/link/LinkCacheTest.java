package net.exylia.link.link;

import net.exylia.link.gateway.Frames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCacheTest {

    @Test
    void statusReadsBoostingOnlyWhenLinked() {
        LinkCache.Status boosting = LinkCache.Status.of(Frames.parse("{\"linked\":true,\"discordId\":\"1\",\"discordName\":\"dig\",\"boosting\":true}"), false);
        assertTrue(boosting.linked());
        assertTrue(boosting.boosting());

        // An older gateway sends no `boosting`: not boosting, still linked.
        LinkCache.Status old = LinkCache.Status.of(Frames.parse("{\"linked\":true,\"discordId\":\"1\"}"), false);
        assertTrue(old.linked());
        assertFalse(old.boosting());

        assertEquals(LinkCache.Status.UNLINKED, LinkCache.Status.of(Frames.parse("{\"linked\":false,\"boosting\":true}"), true));
    }
}
