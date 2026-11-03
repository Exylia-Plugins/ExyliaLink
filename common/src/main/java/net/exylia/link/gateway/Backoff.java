package net.exylia.link.gateway;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Reconnect delays: exponential with full jitter, {@code random(0, min(60 s, 1 s × 2^attempt))},
 * the attempt count reset once a connection stayed up for a minute.
 */
public final class Backoff {

    static final long BASE_MILLIS = 1_000L;
    static final long MAX_MILLIS = 60_000L;
    static final long STABLE_MILLIS = 60_000L;

    private final LongUnaryOperator random;
    private int attempt;

    /** @param random given a bound, a value in {@code [0, bound]} */
    Backoff(LongUnaryOperator random) {
        this.random = random;
    }

    public Backoff() {
        this(bound -> ThreadLocalRandom.current().nextLong(bound + 1));
    }

    public synchronized long next() {
        long ceiling = Math.min(MAX_MILLIS, BASE_MILLIS << Math.min(attempt, 16));
        attempt++;
        return random.applyAsLong(ceiling);
    }

    /** A connection closed after {@code upMillis}: one that lasted a minute starts over. */
    public synchronized void closedAfter(long upMillis) {
        if (upMillis >= STABLE_MILLIS) {
            attempt = 0;
        }
    }
}
