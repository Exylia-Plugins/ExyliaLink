package net.exylia.link.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStoreTest {

    @TempDir
    Path folder;

    @Test
    void storesTheSecretSealedAndReadsItBack() throws Exception {
        NetworkStore store = new NetworkStore(folder.resolve("network.dat"));
        assertTrue(store.read().isEmpty());
        store.save(new NetworkStore.Network("eln_secret", "ws_1", "ExyliaMC", 42L));
        String onDisk = Files.readString(folder.resolve("network.dat"), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("eln_secret"), "never in plain text on disk");
        NetworkStore.Network read = store.read().orElseThrow();
        assertEquals("eln_secret", read.secret());
        assertEquals("ExyliaMC", read.workspaceName());
        assertEquals(42L, read.linkedAt());
    }

    @Test
    void deletesOnlyTheSecretThatWasRefused() throws Exception {
        NetworkStore store = new NetworkStore(folder.resolve("network.dat"));
        store.save(new NetworkStore.Network("eln_new", "ws_1", "ExyliaMC", 1L));
        assertFalse(store.deleteIf("eln_old"));
        assertTrue(store.read().isPresent());
        assertTrue(store.deleteIf("eln_new"));
        assertTrue(store.read().isEmpty());
    }

    @Test
    void aDamagedOrTamperedFileReadsAsNotLinked() throws Exception {
        Path file = folder.resolve("network.dat");
        NetworkStore store = new NetworkStore(file);
        Files.writeString(file, "{not json");
        assertTrue(store.read().isEmpty());
        String sealed = NetworkStore.seal("eln_secret");
        char flipped = sealed.charAt(10) == 'A' ? 'B' : 'A';
        Files.writeString(file, "{\"secret\":\"" + sealed.substring(0, 10) + flipped + sealed.substring(11) + "\"}");
        assertTrue(store.read().isEmpty(), "GCM refuses a changed byte");
    }
}
