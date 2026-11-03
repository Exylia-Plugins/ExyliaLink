package net.exylia.exyliaLink.link;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The network secret, one row in the shared database ({@code exylialink_network}), read by every
 * server on start and every 30 s, never written to a config file.
 *
 * <p>Sealed with AES-GCM (JDK only). Every server sharing the database must open it, so the key
 * comes from a constant of the plugin: that keeps the secret out of a database dump read on its
 * own, not away from somebody holding both the dump and the jar.
 */
public final class NetworkStore {

    private static final String ROW = "network";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] KEY = sha256("ExyliaLink/network-secret/v1");

    @Table(value = "exylialink_network", data = DataKind.SETUP, cache = false)
    public record NetworkRow(
            @Id(length = 16) String id,
            @Column(length = 512) String secret,
            @Column(value = "workspace_id", length = 64) String workspaceId,
            @Column(value = "workspace_name", length = 128) String workspaceName,
            @Column(value = "linked_at") long linkedAt) {
    }

    /** The link as read back, secret opened. */
    public record Network(String secret, String workspaceId, String workspaceName, long linkedAt) {
    }

    private final Repository<NetworkRow> rows;

    public NetworkStore(@NotNull PluginDatabase database) {
        this.rows = database.repository(NetworkRow.class);
    }

    /** Empty when unlinked, or when the stored value cannot be opened (another key: link again). */
    public @NotNull CompletableFuture<Optional<Network>> read() {
        return rows.find(ROW).thenApply(found -> found.flatMap(row -> {
            try {
                return Optional.of(new Network(open(row.secret()), row.workspaceId(), row.workspaceName(), row.linkedAt()));
            } catch (GeneralSecurityException | IllegalArgumentException unreadable) {
                return Optional.empty();
            }
        }));
    }

    public @NotNull CompletableFuture<Void> save(@NotNull Network network) {
        return rows.save(new NetworkRow(ROW, seal(network.secret()), network.workspaceId(),
                network.workspaceName(), network.linkedAt()));
    }

    public @NotNull CompletableFuture<Boolean> delete() {
        return rows.delete(ROW);
    }

    /** Deletes the row only while it still holds {@code secret}: another server may have linked again meanwhile. */
    public @NotNull CompletableFuture<Boolean> deleteIf(@NotNull String secret) {
        return read().thenCompose(current -> current.isPresent() && current.get().secret().equals(secret)
                ? delete() : CompletableFuture.completedFuture(false));
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

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
