package net.exylia.exyliaLink.importer;

import net.exylia.exyliaLink.ExyliaLink;
import net.exylia.exyliaLink.config.LinkMessages;
import net.exylia.exyliaLink.gateway.Frames;
import net.exylia.exyliaLink.gateway.RequestException;
import net.exylia.lib.text.Values;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /linkadmin import discordsrv}: DiscordSRV's links streamed to Link as {@code import.batch}
 * requests of up to 1 000 pairs, one in flight, the last one {@code final}.
 *
 * <p>Read through DiscordSRV's API when it runs here (whatever its storage), else from its
 * {@code accounts.aof}, else from the SQL table its config points at.
 */
public final class DiscordSrvImport {

    private static final String DEFAULT_JDBC = "jdbc:mysql://HOST:PORT/DATABASE?autoReconnect=true&useSSL=false";
    private static final int MAX_OFFLINE_RETRIES = 3;

    private final ExyliaLink plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    public DiscordSrvImport(@NotNull ExyliaLink plugin) {
        this.plugin = plugin;
    }

    record Read(String from, Map<String, String> links) {
    }

    /** One run: its id, its pairs and the totals so far. */
    private final class Run {
        final CommandSender sender;
        final String importId = UUID.randomUUID().toString();
        final List<Frames.Pair> pairs;
        int sent;
        int inserted;
        int skipped;
        int conflicts;

        Run(CommandSender sender, List<Frames.Pair> pairs) {
            this.sender = sender;
            this.pairs = pairs;
        }
    }

    public void start(@NotNull CommandSender sender) {
        LinkMessages.Import lines = plugin.messages().importing();
        if (!plugin.connection().welcomed()) {
            plugin.say(sender, plugin.messages().errors().offline(), Values.of());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            plugin.say(sender, lines.running(), Values.of());
            return;
        }
        plugin.tasks().runAsync(() -> {
            Read read;
            try {
                read = read();
            } catch (Exception failure) {
                running.set(false);
                plugin.debug().warn("DiscordSRV import: could not read the links: " + failure);
                plugin.say(sender, lines.unreadable(), Values.of("error", String.valueOf(failure.getMessage())));
                return;
            }
            if (read == null || read.links().isEmpty()) {
                running.set(false);
                plugin.say(sender, lines.none(), Values.of());
                return;
            }
            List<Frames.Pair> pairs = new ArrayList<>(read.links().size());
            read.links().forEach((discord, uuid) -> pairs.add(new Frames.Pair(uuid, discord)));
            plugin.say(sender, lines.started(), Values.of("total", pairs.size()).put("source", read.from()));
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
                    plugin.tasks().runAsyncLater(seconds * 20, () -> batch(run, attempt));
                } else if (RequestException.OFFLINE.equals(error.code()) && attempt < MAX_OFFLINE_RETRIES) {
                    // Resending a batch is harmless: its pairs come back as skipped.
                    plugin.tasks().runAsyncLater(100L, () -> batch(run, attempt + 1));
                } else {
                    running.set(false);
                    plugin.say(run.sender, plugin.messages().importing().failed(), Values.of("sent", run.sent)
                            .put("total", run.pairs.size()).put("error", error.code()));
                }
                return;
            }
            int inserted = (int) number(data, "inserted");
            int skipped = (int) number(data, "skipped");
            run.inserted += inserted;
            run.skipped += skipped;
            run.conflicts += Math.max(0, pairs.size() - inserted - skipped);
            if (data.has("conflicts") && data.get("conflicts").isJsonArray()) {
                data.getAsJsonArray("conflicts").forEach(conflict -> plugin.debug().log("DiscordSRV import conflict: "
                        + conflict.getAsJsonObject().get("uuid") + " / " + conflict.getAsJsonObject().get("discordId")
                        + " (" + conflict.getAsJsonObject().get("reason") + ")"));
            }
            run.sent = end;
            Values values = Values.of("sent", run.sent).put("total", run.pairs.size()).put("inserted", run.inserted)
                    .put("skipped", run.skipped).put("conflicts", run.conflicts);
            if (last) {
                running.set(false);
                plugin.say(run.sender, plugin.messages().importing().done(), values);
            } else {
                plugin.say(run.sender, plugin.messages().importing().progress(), values);
                batch(run, 0);
            }
        });
    }

    private static long number(com.google.gson.JsonObject data, String key) {
        Long value = Frames.longOrNull(data, key);
        return value == null ? 0 : value;
    }

    // ---------------------------------------------------------------- reading

    private static Read read() throws Exception {
        if (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            return new Read("DiscordSRV", DiscordSrvApi.links());
        }
        File folder = new File(Bukkit.getPluginsFolder(), "DiscordSRV");
        File aof = new File(folder, "accounts.aof");
        if (aof.isFile()) {
            return new Read("accounts.aof", AofParser.parse(Files.readString(aof.toPath(), StandardCharsets.UTF_8)));
        }
        File config = new File(folder, "config.yml");
        if (!config.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
        String url = yaml.getString("Experiment_JdbcAccountLinkBackend", "");
        if (url == null || !url.startsWith("jdbc:") || url.equals(DEFAULT_JDBC)) {
            return null;
        }
        String prefix = yaml.getString("Experiment_JdbcTablePrefix", "discordsrv");
        String table = (prefix == null || prefix.isBlank() ? "" : prefix + "_") + "accounts";
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("unexpected table name " + table);
        }
        Map<String, String> links = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, yaml.getString("Experiment_JdbcUsername"),
                yaml.getString("Experiment_JdbcPassword"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT discord, uuid FROM " + table)) {
            while (rows.next()) {
                links.put(rows.getString(1), rows.getString(2));
            }
        }
        return new Read("SQL (" + table + ")", links);
    }
}
