package net.exylia.link.importer;

import com.google.gson.JsonObject;
import net.exylia.link.LinkCore;
import net.exylia.link.gateway.Frames;
import net.exylia.link.gateway.RequestException;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /linkadmin import discordsrv}: DiscordSRV's links streamed to Link as {@code import.batch}
 * requests of up to 1 000 pairs, one in flight, the last one {@code final}. Where the links come
 * from (DiscordSRV's API, its {@code accounts.aof}, its SQL table) is the backend's business;
 * a proxy has no reader and says so.
 */
public final class LinkImport {

    private static final int MAX_OFFLINE_RETRIES = 3;

    /** What a reader found: where from, and Discord id to Minecraft UUID. */
    public record Read(@NotNull String from, @NotNull Map<String, String> links) {
    }

    private final LinkCore plugin;
    private final @Nullable Callable<Read> reader;
    private final AtomicBoolean running = new AtomicBoolean();

    /** @param reader blocking, called off the server's threads; {@code null} where DiscordSRV cannot run (proxies) */
    public LinkImport(@NotNull LinkCore plugin, @Nullable Callable<Read> reader) {
        this.plugin = plugin;
        this.reader = reader;
    }

    /** One run: its id, its pairs and the totals so far. */
    private static final class Run {
        final Sender sender;
        final String importId = UUID.randomUUID().toString();
        final List<Frames.Pair> pairs;
        int sent;
        int inserted;
        int skipped;
        int conflicts;

        Run(Sender sender, List<Frames.Pair> pairs) {
            this.sender = sender;
            this.pairs = pairs;
        }
    }

    public void start(@NotNull Sender sender) {
        if (reader == null) {
            plugin.say(sender, "importing.backend-only", Values.of());
            return;
        }
        if (!plugin.connection().welcomed()) {
            plugin.say(sender, "errors.offline", Values.of());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            plugin.say(sender, "importing.running", Values.of());
            return;
        }
        plugin.scheduler().async(() -> {
            Read read;
            try {
                read = reader.call();
            } catch (Exception failure) {
                running.set(false);
                plugin.warn("DiscordSRV import: could not read the links: " + failure);
                plugin.say(sender, "importing.unreadable", Values.of().putText("error", String.valueOf(failure.getMessage())));
                return;
            }
            if (read == null || read.links().isEmpty()) {
                running.set(false);
                plugin.say(sender, "importing.none", Values.of());
                return;
            }
            List<Frames.Pair> pairs = new ArrayList<>(read.links().size());
            read.links().forEach((discord, uuid) -> pairs.add(new Frames.Pair(uuid, discord)));
            plugin.say(sender, "importing.started", Values.of("total", pairs.size()).putText("source", read.from()));
            batch(new Run(sender, pairs), 0);
        });
    }

    private void batch(Run run, int attempt) {
        int end = Math.min(run.pairs.size(), run.sent + Frames.MAX_PAIRS);
        List<Frames.Pair> pairs = run.pairs.subList(run.sent, end);
        boolean last = end == run.pairs.size();
        plugin.connection().request("import.batch", Frames.map("importId", run.importId, "source", "discordsrv",
                "pairs", pairs, "final", last)).whenComplete((data, failure) -> {
            if (failure != null) {
                RequestException error = RequestException.of(failure);
                if ("rate_limited".equals(error.code())) {
                    long seconds = error.retryAfter() == null ? 5 : Math.max(1, error.retryAfter());
                    plugin.scheduler().later(seconds * 1000, () -> batch(run, attempt));
                } else if (RequestException.OFFLINE.equals(error.code()) && attempt < MAX_OFFLINE_RETRIES) {
                    // Resending a batch is harmless: its pairs come back as skipped.
                    plugin.scheduler().later(5_000, () -> batch(run, attempt + 1));
                } else {
                    running.set(false);
                    plugin.say(run.sender, "importing.failed", Values.of("sent", run.sent)
                            .put("total", run.pairs.size()).putText("error", error.code()));
                }
                return;
            }
            int inserted = (int) number(data, "inserted");
            int skipped = (int) number(data, "skipped");
            run.inserted += inserted;
            run.skipped += skipped;
            run.conflicts += Math.max(0, pairs.size() - inserted - skipped);
            if (data.has("conflicts") && data.get("conflicts").isJsonArray()) {
                data.getAsJsonArray("conflicts").forEach(conflict -> plugin.info("DiscordSRV import conflict: "
                        + conflict.getAsJsonObject().get("uuid") + " / " + conflict.getAsJsonObject().get("discordId")
                        + " (" + conflict.getAsJsonObject().get("reason") + ")"));
            }
            run.sent = end;
            Values values = Values.of("sent", run.sent).put("total", run.pairs.size()).put("inserted", run.inserted)
                    .put("skipped", run.skipped).put("conflicts", run.conflicts);
            if (last) {
                running.set(false);
                plugin.say(run.sender, "importing.done", values);
            } else {
                plugin.say(run.sender, "importing.progress", values);
                batch(run, 0);
            }
        });
    }

    private static long number(JsonObject data, String key) {
        Long value = Frames.longOrNull(data, key);
        return value == null ? 0 : value;
    }
}
