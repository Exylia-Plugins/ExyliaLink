package net.exylia.link.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @TempDir Path plugins;

    private void lib(String serverId) throws Exception {
        Path dir = Files.createDirectories(plugins.resolve("ExyliaLib"));
        Files.writeString(dir.resolve("database.yml"),
                "database:\n  type: mysql\n  redis:\n    enabled: true\n    server-id: '" + serverId + "'\n", StandardCharsets.UTF_8);
    }

    @Test
    void blankIdComesFromExyliaLibAndIsWrittenBack() throws Exception {
        lib("lobby");
        Path folder = plugins.resolve("ExyliaLink");
        assertEquals("lobby", Settings.load(folder, false).serverId());
        assertTrue(Files.readString(folder.resolve("config.yml")).contains("server-id: 'lobby'"));
        lib("changed");
        assertEquals("lobby", Settings.load(folder, false).serverId(), "once written it stays");
    }

    @Test
    void explicitIdWins() throws Exception {
        lib("lobby");
        Path folder = Files.createDirectories(plugins.resolve("ExyliaLink"));
        Files.writeString(folder.resolve("config.yml"), "server-id: 'hub'\n");
        assertEquals("hub", Settings.load(folder, false).serverId());
    }

    @Test
    void libDefaultIsIgnored() throws Exception {
        lib("server-1");
        assertEquals("proxy", Settings.load(plugins.resolve("ExyliaLink"), true).serverId());
        assertTrue(Settings.load(plugins.resolve("Other"), false).serverId().matches("server-[a-z2-9]{6}"));
    }

    @Test
    void noLibOnAProxyIsProxy() throws Exception {
        assertEquals("proxy", Settings.load(plugins.resolve("ExyliaLink"), true).serverId());
    }
}
