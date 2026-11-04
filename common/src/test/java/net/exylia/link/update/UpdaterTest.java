package net.exylia.link.update;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @Test
    void comparesVersions() {
        assertTrue(Updater.isNewer("v1.0.1", "1.0.0"));
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"));
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"));
        assertTrue(Updater.isNewer("1.0.1", "1.0.0-SNAPSHOT"));
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"));
        assertFalse(Updater.isNewer("0.9.9", "1.0.0"), "never downgrades");
        assertFalse(Updater.isNewer("1.1.0-beta.1", "1.0.0"), "pre-releases are ignored");
        assertFalse(Updater.isNewer("latest", "1.0.0"));
        assertFalse(Updater.isNewer(null, "1.0.0"));
        assertFalse(Updater.isNewer("1.0.1", "dev"));
    }

    @Test
    void picksTheFixedNameJarAndItsChecksum() {
        Updater.Asset asset = Updater.asset(JsonParser.parseString("""
                {"assets": [
                  {"name": "ExyliaLink-1.0.1.jar", "browser_download_url": "https://x/ExyliaLink-1.0.1.jar"},
                  {"name": "ExyliaLink.jar", "browser_download_url": "https://x/ExyliaLink.jar", "digest": "sha256:abc"},
                  {"name": "ExyliaLink.jar.sha256", "browser_download_url": "https://x/ExyliaLink.jar.sha256"}
                ]}""").getAsJsonObject());
        assertEquals(new Updater.Asset("https://x/ExyliaLink.jar", "https://x/ExyliaLink.jar.sha256", "abc"), asset);
        assertNull(Updater.asset(JsonParser.parseString("""
                {"assets": [{"name": "ExyliaLink-1.0.1.jar", "browser_download_url": "https://x/a.jar"}]}""")
                .getAsJsonObject()));
        assertNull(Updater.asset(JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    void readsTheSha256File() {
        String hash = "A".repeat(64);
        assertEquals(hash.toLowerCase(), Updater.parseSha256(hash + "  ExyliaLink.jar\n"));
        assertNull(Updater.parseSha256("not a hash"));
    }

    @Test
    void installsIntoTheUpdateFolderOrOverTheRunningJar(@TempDir Path dir) throws Exception {
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Path jar = Files.createFile(plugins.resolve("ExyliaLink-1.0.0.jar"));
        // Paper, Spigot, Folia: the update folder, under the running jar's file name.
        assertEquals(plugins.resolve("update/ExyliaLink-1.0.0.jar"), Updater.target(jar, plugins.resolve("update")));
        // Velocity, BungeeCord: the running jar itself.
        assertEquals(jar, Updater.target(jar, null));
        // Paper's remapped copy stands for the jar in plugins/.
        Path remapped = Files.createDirectories(plugins.resolve(".paper-remapped")).resolve("ExyliaLink-1.0.0.jar");
        Files.createFile(remapped);
        assertEquals(jar, Updater.original(remapped));
        assertEquals(jar, Updater.original(jar));
    }
}
