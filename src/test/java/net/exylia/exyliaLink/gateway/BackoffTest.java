package net.exylia.exyliaLink.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackoffTest {

    @Test
    void ceilingDoublesUpToAMinuteAndResetsAfterAStableConnection() {
        Backoff backoff = new Backoff(bound -> bound);
        assertEquals(1_000, backoff.next());
        assertEquals(2_000, backoff.next());
        assertEquals(4_000, backoff.next());
        for (int i = 0; i < 30; i++) {
            backoff.next();
        }
        assertEquals(60_000, backoff.next());
        backoff.closedAfter(59_999);
        assertEquals(60_000, backoff.next());
        backoff.closedAfter(60_000);
        assertEquals(1_000, backoff.next());
    }

    @Test
    void fullJitterDrawsFromZero() {
        Backoff backoff = new Backoff(bound -> 0);
        assertEquals(0, backoff.next());
    }
}
