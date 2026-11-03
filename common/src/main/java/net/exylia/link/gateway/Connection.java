package net.exylia.link.gateway;

import com.google.gson.JsonObject;
import net.exylia.link.LinkCore;
import net.exylia.link.store.NetworkStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This server's link to the gateway: the stored network secret, registration, the socket, the
 * requests in flight and the frames that arrive.
 *
 * <p>Every state change happens under this object's lock: the one-second tick (on the plugin's
 * own scheduler) and the socket's callbacks, which the JDK delivers one at a time per socket and which ask
 * for the next frame only once the current one is handled, so frames are processed in order.
 * Nothing here blocks on the server's threads.
 */
public final class Connection implements WebSocket.Listener {

    public enum State { UNLINKED, REGISTERING, CONNECTING, OPEN, WELCOMED, STOPPED }

    private static final long RETRY_LATER_MILLIS = 10 * 60_000L;
    private static final long READ_NETWORK_EVERY_MILLIS = 30_000L;
    private static final long SNAPSHOT_EVERY_MILLIS = 5 * 60_000L;
    private static final long PING_EVERY_MILLIS = 30_000L;
    private static final long REQUEST_TIMEOUT_SECONDS = 15;

    private final LinkCore plugin;
    private final NetworkStore store;
    private final GatewayHttp http;
    private final Backoff backoff = new Backoff();
    private final UUID instanceId = UUID.randomUUID();
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, CompletableFuture<JsonObject>> requests = new ConcurrentHashMap<>();

    private volatile State state = State.UNLINKED;
    private volatile NetworkStore.Network network;
    private volatile String lastError = "";
    private volatile String workspaceName;
    private volatile String linkPageUrl;
    private volatile long latencyMillis = -1;
    private String token;
    private WebSocket socket;
    private final StringBuilder incoming = new StringBuilder();
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
    private long connectedAt;
    private long waitUntil;
    private long lastNetworkRead;
    private boolean announced;
    private long lastSnapshot;
    private long lastPing;
    private volatile boolean stoppedForProtocol;

    public Connection(@NotNull LinkCore plugin, @NotNull NetworkStore store, @NotNull GatewayHttp http) {
        this.plugin = plugin;
        this.store = store;
        this.http = http;
    }

    // ------------------------------------------------------------------ reads

    public State state() {
        return state;
    }

    public boolean welcomed() {
        return state == State.WELCOMED;
    }

    public @Nullable NetworkStore.Network network() {
        return network;
    }

    public @Nullable String workspaceName() {
        return workspaceName != null ? workspaceName : network == null ? null : network.workspaceName();
    }

    public @Nullable String linkPageUrl() {
        return linkPageUrl;
    }

    public long latencyMillis() {
        return latencyMillis;
    }

    /** Exylia Link refused this protocol version (close 4002): nothing reconnects until an update. */
    public boolean outdated() {
        return stoppedForProtocol;
    }

    public String lastError() {
        return lastError;
    }

    public UUID instanceId() {
        return instanceId;
    }

    // -------------------------------------------------------------- lifecycle

    /** Reads the network again at the next tick: a connect or disconnect made here. */
    public synchronized void relink() {
        lastNetworkRead = 0;
        waitUntil = 0;
        stoppedForProtocol = false;
    }

    public synchronized void stop() {
        state = State.STOPPED;
        failRequests("plugin disabled");
        WebSocket open = socket;
        socket = null;
        if (open != null) {
            open.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disabled");
            open.abort();
        }
    }

    /** Every second, on the plugin's scheduler. */
    public synchronized void tick() {
        if (state == State.STOPPED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNetworkRead >= READ_NETWORK_EVERY_MILLIS) {
            lastNetworkRead = now;
            try {
                networkRead(store.read().orElse(null));
            } catch (Exception failure) {
                plugin.warn("Could not read " + store.file().getFileName() + ": " + failure.getMessage());
            }
        }
        if (network == null || stoppedForProtocol || now < waitUntil) {
            return;
        }
        if (token == null && state == State.UNLINKED) {
            register();
            return;
        }
        if (socket == null && state == State.UNLINKED && token != null) {
            connect();
            return;
        }
        if (state != State.WELCOMED) {
            return;
        }
        if (now - lastSnapshot >= SNAPSHOT_EVERY_MILLIS) {
            lastSnapshot = now;
            send(Frames.snapshot(plugin.presence().online()));
        }
        if (now - lastPing >= PING_EVERY_MILLIS) {
            lastPing = now;
            ByteBuffer stamp = ByteBuffer.allocate(Long.BYTES).putLong(System.nanoTime()).flip();
            socket.sendPing(stamp);
        }
    }

    private void networkRead(@Nullable NetworkStore.Network read) {
        String before = network == null ? null : network.secret();
        network = read;
        boolean first = !announced;
        announced = true;
        if (first || !Objects.equals(before, read == null ? null : read.secret())) {
            // A new secret (linked again) or none: the token and socket belonged to the old one.
            token = null;
            closeSocket("network changed");
            state = State.UNLINKED;
            waitUntil = 0;
            plugin.info(read == null ? "Not connected to Exylia Link: run /linkadmin connect to link this server."
                    : "Linked to the Exylia Link network " + read.workspaceName() + ".");
        }
    }

    // ----------------------------------------------------------- registration

    private void register() {
        state = State.REGISTERING;
        NetworkStore.Network current = network;
        try {
            GatewayHttp.Response response = http.post("/v1/servers/register", Frames.map(
                    "serverId", Frames.clip(plugin.serverId(), 64),
                    "instanceId", instanceId.toString(),
                    "pluginVersion", plugin.pluginVersion(),
                    "platform", plugin.platform().platform(),
                    "minecraftVersion", plugin.minecraftVersion(),
                    "protocol", Frames.PROTOCOL), current.secret());
            switch (response.status()) {
                case 200 -> {
                    token = response.string("serverToken");
                    lastError = "";
                }
                case 401 -> {
                    lastError = "network secret revoked";
                    plugin.warn("Exylia Link refused this network's secret: the network is no longer linked.");
                    network = null;
                    try {
                        store.deleteIf(current.secret());
                    } catch (Exception failure) {
                        plugin.warn("Could not delete " + store.file().getFileName() + ": " + failure.getMessage());
                    }
                    relink();
                    plugin.secretRevoked();
                }
                case 400 -> {
                    plugin.warn("Exylia Link refused the registration as malformed; this is a bug, please report it.");
                    later("registration refused (invalid_body)");
                }
                case 403 -> later("network suspended");
                default -> retry("register answered " + response.status(), response.retryAfterSeconds());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            retry("register interrupted", 0);
        } catch (Exception failure) {
            retry("register failed: " + failure.getMessage(), 0);
        }
        if (state == State.REGISTERING) {
            state = State.UNLINKED;
        }
    }

    private void connect() {
        state = State.CONNECTING;
        incoming.setLength(0);
        try {
            http.connect(token, this).whenComplete((open, failure) -> {
                if (failure != null) {
                    connectFailed(failure);
                }
            });
        } catch (RuntimeException badUrl) {
            state = State.UNLINKED;
            retry("connect failed: " + badUrl.getMessage(), 0);
        }
    }

    private synchronized void connectFailed(Throwable failure) {
        if (state != State.CONNECTING) {
            return;
        }
        state = State.UNLINKED;
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if (status == 401) {
                // Unknown or rotated token: register again, then reconnect.
                token = null;
                lastError = "server token refused";
                return;
            }
            if (status == 403) {
                later("network suspended");
                return;
            }
        }
        retry("connect failed: " + cause.getMessage(), 0);
    }

    private void later(String why) {
        lastError = why;
        waitUntil = System.currentTimeMillis() + RETRY_LATER_MILLIS;
        plugin.warn("Exylia Link: " + why + "; trying again in 10 minutes.");
    }

    private void retry(String why, long retryAfterSeconds) {
        lastError = why;
        long delay = Math.max(backoff.next(), retryAfterSeconds * 1000);
        waitUntil = System.currentTimeMillis() + delay;
        plugin.debug("Exylia Link: " + why + "; retrying in " + delay + " ms");
    }

    private void closeSocket(String reason) {
        WebSocket open = socket;
        socket = null;
        failRequests(reason);
        if (open != null) {
            open.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            open.abort();
        }
        if (state == State.OPEN || state == State.WELCOMED || state == State.CONNECTING) {
            state = State.UNLINKED;
        }
    }

    // ----------------------------------------------------------------- socket

    @Override
    public void onOpen(WebSocket webSocket) {
        synchronized (this) {
            if (state != State.CONNECTING) {
                webSocket.abort();
                return;
            }
            socket = webSocket;
            connectedAt = System.currentTimeMillis();
            state = State.OPEN;
            sendNow(Frames.hello(plugin.platform().kind(), plugin.pluginVersion(), plugin.serverId(), instanceId,
                    plugin.platform().platform(), plugin.minecraftVersion(), plugin.presence().online()));
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (this) {
            if (webSocket == socket) {
                incoming.append(data);
                if (last) {
                    String text = incoming.toString();
                    incoming.setLength(0);
                    try {
                        frame(Frames.parse(text));
                    } catch (RuntimeException failure) {
                        plugin.warn("Exylia Link: could not handle a frame: " + failure);
                    }
                }
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        if (message.remaining() == Long.BYTES) {
            latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - message.getLong());
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
        closed(webSocket, code, reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed(webSocket, -1, String.valueOf(error.getMessage()));
    }

    private synchronized void closed(WebSocket which, int code, String reason) {
        if (which != socket) {
            // A stale socket (4004 replaced included): the current one carries on.
            return;
        }
        socket = null;
        state = State.UNLINKED;
        latencyMillis = -1;
        failRequests("connection closed");
        plugin.networkState(null);
        backoff.closedAfter(System.currentTimeMillis() - connectedAt);
        lastError = "closed " + code + (reason == null || reason.isBlank() ? "" : " (" + reason + ")");
        switch (code) {
            case 4001 -> token = null;
            case 4002 -> {
                stoppedForProtocol = true;
                lastError = "this ExyliaLink version is no longer supported";
                plugin.error("Exylia Link no longer supports this ExyliaLink version: update the plugin.");
                plugin.protocolRefused();
                return;
            }
            case 4003 -> {
                later("network suspended");
                return;
            }
            default -> {
            }
        }
        waitUntil = System.currentTimeMillis() + backoff.next();
    }

    private void frame(JsonObject frame) {
        String type = Frames.string(frame, "t");
        switch (type) {
            case "welcome" -> welcome(frame);
            case "reply" -> reply(frame);
            case "link.updated" -> plugin.linkUpdated(frame);
            case "requirements.updated" -> plugin.requirements().update(frame);
            case "network.state" -> plugin.networkState(Frames.bool(frame, "proxy"));
            case "reward.deliver" -> plugin.rewards().deliver(frame);
            case "error" -> plugin.warn("Exylia Link: " + Frames.string(frame, "code") + ": " + Frames.string(frame, "message"));
            default -> plugin.debug("Exylia Link: ignored frame " + type);
        }
    }

    private void welcome(JsonObject frame) {
        state = State.WELCOMED;
        lastError = "";
        workspaceName = Frames.stringOrNull(frame, "workspaceName");
        linkPageUrl = Frames.stringOrNull(frame, "linkPageUrl");
        // Players who joined between hello and welcome had their join dropped: one full list covers them.
        lastSnapshot = System.currentTimeMillis();
        sendNow(Frames.snapshot(plugin.presence().online()));
        lastPing = 0;
        // Absent from an older gateway: nothing required, and no proxy known (backends behave as before).
        plugin.networkState(frame.has("network") && frame.get("network").isJsonObject()
                ? Frames.bool(frame.getAsJsonObject("network"), "proxy") : null);
        plugin.requirements().update(frame.get("requirements"));
        plugin.info("Connected to Exylia Link (" + workspaceName + ").");
        plugin.welcomed();
    }

    private void reply(JsonObject frame) {
        CompletableFuture<JsonObject> pending = requests.remove(Frames.string(frame, "id"));
        if (pending == null) {
            return;
        }
        if (Frames.bool(frame, "ok")) {
            pending.complete(frame.has("data") && frame.get("data").isJsonObject() ? frame.getAsJsonObject("data") : new JsonObject());
        } else {
            pending.completeExceptionally(Frames.error(frame));
        }
    }

    // --------------------------------------------------------------- outgoing

    /**
     * Asks the gateway; completes with the reply's {@code data}, or fails with a
     * {@link RequestException}: the gateway's error, or {@code offline} when the socket is down
     * or 15 s pass without a reply.
     */
    public CompletableFuture<JsonObject> request(@NotNull String type, @NotNull Map<String, Object> args) {
        CompletableFuture<JsonObject> reply = new CompletableFuture<>();
        synchronized (this) {
            if (state != State.WELCOMED) {
                reply.completeExceptionally(RequestException.offline("not connected"));
                return reply;
            }
            String id = "r-" + requestIds.incrementAndGet();
            requests.put(id, reply);
            reply.whenComplete((data, failure) -> requests.remove(id));
            sendNow(Frames.request(id, type, args));
        }
        return reply.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Presence and acks: sent only while welcomed, never queued (hello resynchronises). */
    public synchronized void send(@NotNull String frame) {
        if (state == State.WELCOMED) {
            sendNow(frame);
        }
    }

    /** Text frames must not overlap, so each waits for the one before it. */
    private void sendNow(String text) {
        WebSocket open = socket;
        if (open == null) {
            return;
        }
        sending = sending.handle((ignored, failure) -> null).thenCompose(ignored -> open.sendText(text, true));
    }

    private void failRequests(String why) {
        RequestException offline = RequestException.offline(why);
        requests.values().forEach(pending -> pending.completeExceptionally(offline));
        requests.clear();
    }
}
