package net.exylia.link.config;

import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code config.yml}. Almost nothing: what Link does is set on link.exylia.net, and the network
 * secret lives sealed in {@code network.dat}, never in a file an owner might paste somewhere.
 */
public record Settings(@NotNull String language, @NotNull String serverId, @NotNull String gatewayUrl,
                       int reminderMinutes, boolean debug) {

    public static final String DEFAULT_GATEWAY = "https://link-gateway.exylia.net";
    private static final Pattern SERVER_ID_LINE = Pattern.compile("(?m)^server-id:.*$");

    /**
     * Writes the default file when there is none. A blank {@code server-id} gets a random one,
     * written back so it stays the same across restarts.
     */
    public static @NotNull Settings load(@NotNull Path folder) throws IOException {
        Path file = folder.resolve("config.yml");
        if (!Files.exists(file)) {
            Files.createDirectories(folder);
            try (InputStream in = Settings.class.getResourceAsStream("/config.yml")) {
                Files.copy(in, file);
            }
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Object root = new Yaml().load(text);
        Map<?, ?> yaml = root instanceof Map<?, ?> map ? map : Map.of();
        String serverId = string(yaml, "server-id", "");
        if (serverId.isBlank()) {
            serverId = "server-" + randomSuffix();
            String line = "server-id: '" + serverId + "'";
            Matcher existing = SERVER_ID_LINE.matcher(text);
            text = existing.find() ? existing.replaceFirst(line) : text + "\n" + line + "\n";
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        int minutes;
        try {
            minutes = Math.max(0, Integer.parseInt(string(yaml, "requirements-reminder-minutes", "10")));
        } catch (NumberFormatException notANumber) {
            minutes = 10;
        }
        String gateway = string(yaml, "gateway-url", DEFAULT_GATEWAY);
        return new Settings(string(yaml, "language", "en"), serverId.trim(), gateway.isBlank() ? DEFAULT_GATEWAY : gateway.trim(),
                minutes, "true".equalsIgnoreCase(string(yaml, "debug", "false")));
    }

    private static String string(Map<?, ?> yaml, String key, String fallback) {
        Object value = yaml.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String randomSuffix() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }
}
