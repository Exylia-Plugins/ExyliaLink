package net.exylia.link.gateway;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The gateway's HTTP side (network linking, registration) and the socket's handshake, on the
 * JDK's {@link HttpClient}: it speaks WebSocket too, answers the gateway's pings by itself, and
 * needs no library. Blocking calls, only ever made off the server's threads.
 */
public final class GatewayHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record Response(int status, @Nullable JsonObject body, long retryAfterSeconds) {
        public @Nullable String string(String key) {
            return Frames.stringOrNull(body, key);
        }

        public long number(String key, long fallback) {
            Long value = Frames.longOrNull(body, key);
            return value == null ? fallback : value;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Supplier<String> base;
    private final String userAgent;

    /** @param base the configured gateway URL, read on every call so a reload takes effect */
    public GatewayHttp(@NotNull Supplier<String> base, @NotNull String userAgent) {
        this.base = base;
        this.userAgent = userAgent;
    }

    public Response post(@NotNull String path, @NotNull Map<String, Object> body, @Nullable String bearer)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Frames.json(body), StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject json = null;
        try {
            if (response.body() != null && !response.body().isBlank()) {
                json = Frames.parse(response.body());
            }
        } catch (RuntimeException notJson) {
            // A proxy's error page: the status says enough.
        }
        long retryAfter = response.headers().firstValue("Retry-After").map(GatewayHttp::seconds).orElse(0L);
        return new Response(response.statusCode(), json, retryAfter);
    }

    public CompletableFuture<WebSocket> connect(@NotNull String token, @NotNull WebSocket.Listener listener) {
        return http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", userAgent)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(base().replaceFirst("^http", "ws") + "/v1/socket"), listener);
    }

    private String base() {
        String url = base.get().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static long seconds(String value) {
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException date) {
            return 0;
        }
    }
}
