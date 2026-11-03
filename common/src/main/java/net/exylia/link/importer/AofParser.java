package net.exylia.link.importer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DiscordSRV's {@code accounts.aof}, read as DiscordSRV itself replays it: {@code <discordId> <uuid>}
 * links (a new link replaces whatever either side had), {@code -<discordId> <uuid>} (or just one of
 * the two) unlinks. Pure, unit tested.
 */
public final class AofParser {

    private static final Pattern LINK = Pattern.compile(
            "^(\\d+) ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}).*");
    private static final Pattern DISCORD = Pattern.compile("\\d{17,}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private AofParser() {
    }

    /** Discord id to Minecraft UUID, in file order; lines DiscordSRV would reject are skipped. */
    public static Map<String, String> parse(String text) {
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, String> byUuid = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("-")) {
                for (String token : line.substring(1).split(" ")) {
                    if (DISCORD.matcher(token).matches()) {
                        byUuid.remove(links.remove(token));
                    } else if (UUID.matcher(token).matches()) {
                        links.remove(byUuid.remove(token));
                    }
                }
                continue;
            }
            Matcher link = LINK.matcher(line);
            if (link.matches()) {
                String discord = link.group(1);
                String uuid = link.group(2);
                links.remove(byUuid.remove(uuid));
                byUuid.remove(links.remove(discord));
                links.put(discord, uuid);
                byUuid.put(uuid, discord);
            }
        }
        return links;
    }
}
