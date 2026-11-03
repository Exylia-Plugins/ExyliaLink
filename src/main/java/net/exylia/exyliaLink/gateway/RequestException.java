package net.exylia.exyliaLink.gateway;

import org.jetbrains.annotations.Nullable;

/**
 * A request that did not get a successful reply: the gateway's error code, or {@link #OFFLINE}
 * when the socket is down or the reply did not come back within 15 s.
 */
public final class RequestException extends RuntimeException {

    public static final String OFFLINE = "offline";

    private final String code;
    private final @Nullable Long retryAfter;
    private final @Nullable Long until;

    public RequestException(String code, String message, @Nullable Long retryAfter, @Nullable Long until) {
        super(message, null, false, false);
        this.code = code;
        this.retryAfter = retryAfter;
        this.until = until;
    }

    public static RequestException offline(String why) {
        return new RequestException(OFFLINE, why, null, null);
    }

    public String code() {
        return code;
    }

    /** Seconds to wait, for {@code rate_limited}. */
    public @Nullable Long retryAfter() {
        return retryAfter;
    }

    /** Epoch millis the cooldown ends, for {@code cooldown}. */
    public @Nullable Long until() {
        return until;
    }

    /** The exception behind a future's wrapper, as a request error ({@code internal} when it is something else). */
    public static RequestException of(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof RequestException)) {
            cause = cause.getCause();
        }
        if (cause instanceof RequestException known) {
            return known;
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return offline("no reply within 15 s");
        }
        return new RequestException("internal", String.valueOf(cause.getMessage()), null, null);
    }
}
