package net.exylia.link.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * This install's network secret, {@code network.dat} in the plugin folder (every server and proxy
 * collects its own with {@code /linkadmin connect}): read on start and every 30 s, never written to
 * a config file.
 *
 * <p>Sealed with AES-GCM (JDK only) under a key derived from a constant of the plugin: that keeps
 * the secret out of a backup or a pasted folder read on its own, not away from somebody holding
 * both the file and the jar. Written atomically, owner-only where the file system allows it.
 */
public final class NetworkStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] KEY = sha256("ExyliaLink/network-secret/v1");

    /** The link as read back, secret opened. */
    public record Network(@NotNull String secret, @Nullable String workspaceId, @Nullable String workspaceName, long linkedAt) {
    }

    private final Path file;

    public NetworkStore(@NotNull Path file) {
        this.file = file;
    }

    public @NotNull Path file() {
        return file;
    }

    /** Empty when unlinked, or when the file cannot be opened (damaged, another key: link again). */
    public synchronized @NotNull Optional<Network> read() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return Optional.of(new Network(open(string(json, "secret")), string(json, "workspaceId"),
                    string(json, "workspaceName"), json.has("linkedAt") ? json.get("linkedAt").getAsLong() : 0L));
        } catch (GeneralSecurityException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    public synchronized void save(@NotNull Network network) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("secret", seal(network.secret()));
        json.addProperty("workspaceId", network.workspaceId());
        json.addProperty("workspaceName", network.workspaceName());
        json.addProperty("linkedAt", network.linkedAt());
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // The server folder's own permissions apply.
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized boolean delete() throws IOException {
        return Files.deleteIfExists(file);
    }

    /** Deletes the file only while it still holds {@code secret}: it may have been replaced meanwhile. */
    public synchronized boolean deleteIf(@NotNull String secret) throws IOException {
        Optional<Network> current = read();
        return current.isPresent() && current.get().secret().equals(secret) && delete();
    }

    // ------------------------------------------------------------- sealing

    static String seal(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String open(String value) throws GeneralSecurityException {
        if (value == null || !value.startsWith("v1:")) {
            throw new GeneralSecurityException("unknown format");
        }
        byte[] raw = Base64.getDecoder().decode(value.substring(3));
        if (raw.length < 12 + 16) {
            throw new GeneralSecurityException("too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, raw, 0, 12));
        return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
    }

    private static @Nullable String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
