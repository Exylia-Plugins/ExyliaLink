package net.exylia.link.text;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code lang/<code>/messages.yml}: every line the plugin says. The jar's English and Spanish files
 * are copied to the plugin folder once and never overwritten; a key missing from the server's file
 * reads from the jar's file of the same language, then from the jar's English, so an update that
 * adds a message works without touching what the owner changed.
 *
 * <p>A key holds one line or a list of lines. An empty line is not sent; {@code &r} sends a blank one.
 * {@code %prefix%} is the {@code prefix} key.
 */
public final class Messages {

    public static final List<String> LANGUAGES = List.of("en", "es");

    private final Map<String, Object> own;
    private final Map<String, Object> bundled;
    private final Map<String, Object> english;
    private final String prefix;

    Messages(Map<String, Object> own, Map<String, Object> bundled, Map<String, Object> english) {
        this.own = own;
        this.bundled = bundled;
        this.english = english;
        List<String> prefixLines = raw("prefix");
        this.prefix = prefixLines.isEmpty() ? "" : prefixLines.get(0);
    }

    /** Copies the bundled languages that are missing, then reads {@code language}. */
    public static @NotNull Messages load(@NotNull Path folder, @NotNull String language) throws IOException {
        for (String code : LANGUAGES) {
            Path file = folder.resolve("lang").resolve(code).resolve("messages.yml");
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = bundled(code)) {
                    Files.copy(in, file);
                }
            }
        }
        String code = language.isBlank() ? "en" : language.trim();
        Path file = folder.resolve("lang").resolve(code).resolve("messages.yml");
        Map<String, Object> own = Files.exists(file) ? read(Files.newInputStream(file)) : Map.of();
        Map<String, Object> english = read(bundled("en"));
        Map<String, Object> bundled = LANGUAGES.contains(code) ? read(bundled(code)) : english;
        return new Messages(own, bundled, english);
    }

    /** The jar's files only: tests, and a fallback when the folder cannot be read. */
    public static @NotNull Messages bundled() {
        Map<String, Object> english = read(bundled("en"));
        return new Messages(Map.of(), english, english);
    }

    private static InputStream bundled(String code) {
        InputStream in = Messages.class.getResourceAsStream("/lang/" + code + "/messages.yml");
        if (in == null) {
            throw new IllegalStateException("lang/" + code + "/messages.yml is missing from the jar");
        }
        return in;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            return root instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** The lines of {@code key} ({@code link.status-linked}), placeholders applied, empty lines dropped. */
    public @NotNull List<Text.Line> lines(@NotNull String key, @NotNull Values values) {
        List<Text.Line> lines = new ArrayList<>();
        for (String raw : raw(key)) {
            if (raw.isEmpty()) {
                continue;
            }
            lines.add(Text.line(values.apply(raw.replace("%prefix%", prefix))));
        }
        return lines;
    }

    /** One line as text ({@code admin.state-connected}), for use as a value of another message. */
    public @NotNull String text(@NotNull String key) {
        List<String> raw = raw(key);
        return raw.isEmpty() ? "" : raw.get(0);
    }

    private List<String> raw(String key) {
        Object value = find(own, key);
        if (value == null) value = find(bundled, key);
        if (value == null) value = find(english, key);
        if (value instanceof List<?> list) {
            List<String> lines = new ArrayList<>(list.size());
            list.forEach(line -> lines.add(line == null ? "" : String.valueOf(line)));
            return lines;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static @Nullable Object find(Map<String, Object> root, String key) {
        Object node = root;
        for (String part : key.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(part);
        }
        return node;
    }
}
