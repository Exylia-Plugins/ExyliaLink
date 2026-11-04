package net.exylia.link.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.link.LinkCore;
import net.exylia.link.platform.Sender;
import net.exylia.link.text.Values;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps ExyliaLink up to date from its GitHub releases, the way ExyliaLib's {@code PluginUpdater}
 * keeps the other Exylia plugins (ExyliaLink does not depend on ExyliaLib, so it carries its own).
 *
 * <p>Every release attaches {@code ExyliaLink.jar} (a name that never changes) and
 * {@code ExyliaLink.jar.sha256}. A newer release of the same major is downloaded next to where it
 * goes, its SHA-256 checked, and moved into place: on Paper, Spigot and Folia into the server's
 * update folder under the running jar's file name (the server swaps it in on the next start); on
 * Velocity and BungeeCord over the running jar itself (the proxy keeps the classes it already
 * opened, the next start loads the new jar). A new major may change the config or the protocol,
 * so it is only announced, as is every release with {@code auto-update: false}.
 */
public final class Updater {

    public static final String REPOSITORY = "Exylia-Plugins/ExyliaLink";
    public static final String ASSET = "ExyliaLink.jar";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final long FIRST_CHECK_MILLIS = 30_000;
    private static final long EVERY_MILLIS = 6L * 60 * 60 * 1000;
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern SHA256 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    /** The release asset to download and the SHA-256 it must have, or {@code null} when unpublished. */
    record Asset(@NotNull String url, @Nullable String sha256Url, @Nullable String digest) {
    }

    /** A newer release known this session; {@code installed} once it waits for the restart. */
    private record Notice(@NotNull String version, boolean installed, @NotNull String page) {
    }

    private final LinkCore core;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile @Nullable Notice notice;
    private @Nullable String etag;
    private long blockedUntil;

    public Updater(@NotNull LinkCore core) {
        this.core = core;
    }

    /** First check shortly after start, then every 6 hours; all off the server's threads. */
    public void start() {
        core.scheduler().every(FIRST_CHECK_MILLIS, EVERY_MILLIS, this::check);
    }

    /** Tells an admin who just joined that a restart applies, or a download is waiting for, a newer version. */
    public void greet(@NotNull Sender player) {
        Notice known = notice;
        if (known == null || !player.hasPermission(LinkCore.ADMIN)) {
            return;
        }
        core.say(player, known.installed() ? "update.installed" : "update.available", Values.of()
                .putText("version", known.version())
                .putText("current", core.pluginVersion())
                .put("url", known.page()));
    }

    synchronized void check() {
        if (System.currentTimeMillis() < blockedUntil) {
            return;
        }
        Path running = runningJar();
        if (running == null) {
            core.debug("Update check skipped: the running jar could not be located.");
            return;
        }
        try {
            HttpRequest.Builder request = request(API).header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (etag != null) {
                request.header("If-None-Match", etag);
            }
            HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 304) {
                return;
            }
            if (code == 403 || code == 429) {
                blockedUntil = retryAt(response);
                core.debug("GitHub rate limit reached; next update check after " + blockedUntil + ".");
                return;
            }
            if (code != 200) {
                throw new IOException("GitHub answered HTTP " + code);
            }
            String responseTag = response.headers().firstValue("etag").orElse(null);
            if (handle(JsonParser.parseString(response.body()).getAsJsonObject(), running)) {
                // Only a settled answer is cached: a failed download is tried again next time.
                etag = responseTag;
            }
        } catch (IOException | RuntimeException failure) {
            core.debug("Update check failed: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return whether this release needs nothing more (up to date, announced or installed) */
    private boolean handle(JsonObject release, Path running) throws IOException, InterruptedException {
        if (release.has("prerelease") && release.get("prerelease").getAsBoolean()) {
            return true;
        }
        String latest = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
        String current = core.pluginVersion();
        if (!isNewer(latest, current)) {
            return true;
        }
        latest = latest.startsWith("v") ? latest.substring(1) : latest;
        Notice known = notice;
        if (known != null && known.version().equals(latest)) {
            return true;
        }
        String page = release.has("html_url") ? release.get("html_url").getAsString()
                : "https://github.com/" + REPOSITORY + "/releases/latest";
        if (!core.settings().autoUpdate() || major(latest) != major(current)) {
            notice = new Notice(latest, false, page);
            core.info("ExyliaLink " + latest + " is available (running " + current + ")"
                    + (core.settings().autoUpdate() ? ", a new major version: install it by hand when ready" : "")
                    + ". Download: " + page);
            return true;
        }
        Asset asset = asset(release);
        if (asset == null) {
            throw new IOException("release " + latest + " has no " + ASSET);
        }
        String expected = asset.sha256Url() != null ? parseSha256(text(asset.sha256Url())) : asset.digest();
        if (expected == null) {
            throw new IOException("release " + latest + " publishes no SHA-256 for " + ASSET);
        }
        Path target = target(running, core.platform().updateFolder());
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "ExyliaLink", ".tmp");
        try {
            HttpResponse<Path> download = http.send(request(asset.url()).header("Accept", "application/octet-stream")
                    .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (download.statusCode() != 200) {
                throw new IOException("download returned HTTP " + download.statusCode());
            }
            String actual = sha256(tmp);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 mismatch for " + ASSET + " " + latest);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        notice = new Notice(latest, true, page);
        core.info("ExyliaLink " + latest + " downloaded (running " + current + "): restart to apply it.");
        return true;
    }

    // ------------------------------------------------------------ decisions (tested)

    /**
     * Where the new jar goes: the update folder under the running jar's file name when the platform
     * has one (Bukkit matches them by name), the running jar itself on a proxy. Paper runs a
     * Spigot-mapped plugin from a remapped copy in {@code plugins/.paper-remapped/} with the same
     * file name, so the jar it stands for is the one in {@code plugins/}.
     */
    static @NotNull Path target(@NotNull Path running, @Nullable Path updateFolder) {
        if (updateFolder != null) {
            return updateFolder.resolve(running.getFileName().toString());
        }
        return running;
    }

    /** The jar a remapped copy stands for, or the jar itself. */
    static @NotNull Path original(@NotNull Path loaded) {
        Path folder = loaded.getParent();
        if (folder != null && folder.getFileName() != null && folder.getFileName().toString().equals(".paper-remapped")
                && folder.getParent() != null) {
            Path original = folder.getParent().resolve(loaded.getFileName().toString());
            if (Files.isRegularFile(original)) {
                return original;
            }
        }
        return loaded;
    }

    /** {@code ExyliaLink.jar} and its checksum: the {@code .sha256} asset, else the digest GitHub lists. */
    static @Nullable Asset asset(@NotNull JsonObject release) {
        JsonArray assets = release.has("assets") && release.get("assets").isJsonArray()
                ? release.getAsJsonArray("assets") : new JsonArray();
        String url = null;
        String shaUrl = null;
        String digest = null;
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            String download = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : null;
            if (name.equals(ASSET)) {
                url = download;
                JsonElement listed = asset.get("digest");
                if (listed != null && !listed.isJsonNull() && listed.getAsString().startsWith("sha256:")) {
                    digest = listed.getAsString().substring("sha256:".length());
                }
            } else if (name.equals(ASSET + ".sha256")) {
                shaUrl = download;
            }
        }
        return url == null ? null : new Asset(url, shaUrl, digest);
    }

    /** {@code candidate} is a plain release ({@code 1.2.3}, no pre-release suffix) above {@code current}. */
    static boolean isNewer(@Nullable String candidate, @Nullable String current) {
        int[] a = candidate == null ? null : parse(candidate.trim());
        if (a == null) {
            return false;
        }
        int[] b = current == null ? null : parse(current.trim().split("[-+]", 2)[0]);
        if (b == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    static @Nullable String parseSha256(@NotNull String text) {
        Matcher matcher = SHA256.matcher(text);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static int major(String version) {
        int[] parts = parse(version);
        return parts == null ? -1 : parts[0];
    }

    private static int @Nullable [] parse(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    // ----------------------------------------------------------------- plumbing

    /** The jar this class was loaded from, or {@code null} when it is not one (an IDE run). */
    private static @Nullable Path runningJar() {
        try {
            Path path = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? original(path) : null;
        } catch (Exception unknown) {
            return null;
        }
    }

    /** When GitHub's limit lifts: {@code retry-after}, else {@code x-ratelimit-reset}, else an hour. */
    private static long retryAt(HttpResponse<?> response) {
        long now = System.currentTimeMillis();
        try {
            String after = response.headers().firstValue("retry-after").orElse(null);
            if (after != null) {
                return now + Long.parseLong(after.trim()) * 1000;
            }
            String reset = response.headers().firstValue("x-ratelimit-reset").orElse(null);
            if (reset != null) {
                return Long.parseLong(reset.trim()) * 1000;
            }
        } catch (NumberFormatException malformed) {
            // Fall through to the hour.
        }
        return now + 60L * 60 * 1000;
    }

    private String text(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(url + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ExyliaLink-Updater/" + core.pluginVersion());
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
