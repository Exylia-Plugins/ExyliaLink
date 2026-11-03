package net.exylia.link.paper;

import net.exylia.link.importer.AofParser;
import net.exylia.link.importer.LinkImport;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * DiscordSRV's links, read through its API when it runs here (whatever its storage), else from
 * its {@code accounts.aof}, else from the SQL table its config points at. Blocking: called off
 * the server's threads.
 */
final class DiscordSrvReader implements Callable<LinkImport.Read> {

    private static final String DEFAULT_JDBC = "jdbc:mysql://HOST:PORT/DATABASE?autoReconnect=true&useSSL=false";

    private final File pluginsFolder;

    DiscordSrvReader(File pluginsFolder) {
        this.pluginsFolder = pluginsFolder;
    }

    @Override
    public LinkImport.Read call() throws Exception {
        if (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            return new LinkImport.Read("DiscordSRV", DiscordSrvApi.links());
        }
        File folder = new File(pluginsFolder, "DiscordSRV");
        File aof = new File(folder, "accounts.aof");
        if (aof.isFile()) {
            return new LinkImport.Read("accounts.aof", AofParser.parse(Files.readString(aof.toPath(), StandardCharsets.UTF_8)));
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
        return new LinkImport.Read("SQL (" + table + ")", links);
    }
}
