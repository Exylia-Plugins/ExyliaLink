package net.exylia.link.text;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The message format, read without any platform: legacy codes ({@code &l}, {@code &8}, {@code §a},
 * {@code &#8a51c4}), hex colours ({@code <#8a51c4>}), the Exylia palette tokens ({@code {primary}}),
 * {@code <reset>}, {@code <click:action:value>…</click>} and {@code <hover:show_text:'…'>…</hover>},
 * plus a leading {@code [sound:key|volume|pitch;center]} block. Each platform turns the
 * {@link Span}s into its own chat components; nothing here depends on Adventure or BungeeCord.
 *
 * <p>A colour (legacy or hex) clears bold and the other decorations, as a legacy colour code does
 * in Minecraft, so {@code <#8a51c4>&lTITLE} reads the way it is written. {@code \} keeps the next
 * character literal: {@link #escape} uses it for text that must never be read as formatting.
 */
public final class Text {

    public static final int BOLD = 1, ITALIC = 2, UNDERLINED = 4, STRIKETHROUGH = 8, OBFUSCATED = 16;

    /** One run of text in one style. {@code color} is {@code rrggbb} or {@code null} for the default. */
    public record Span(@NotNull String text, @Nullable String color, int decorations, @Nullable Click click,
                       @Nullable List<Span> hover) {
        public boolean has(int decoration) {
            return (decorations & decoration) != 0;
        }
    }

    /** {@code action}: open_url, run_command, suggest_command or copy_to_clipboard. */
    public record Click(@NotNull String action, @NotNull String value) {
    }

    /** A namespaced sound key ({@code entity.experience_orb.pickup}), played to the player only. */
    public record Sound(@NotNull String key, float volume, float pitch) {
    }

    /** One chat line, ready for a platform. */
    public record Line(@NotNull List<Span> spans, @Nullable Sound sound) {
        public @NotNull String plain() {
            return Text.plain(spans);
        }
    }

    /** The Exylia palette; the only place its values live. */
    public static final Map<String, String> PALETTE = Map.ofEntries(
            Map.entry("primary", "8a51c4"),
            Map.entry("secondary", "aa76de"),
            Map.entry("secondary_light", "b48fd9"),
            Map.entry("letters", "e7cfff"),
            Map.entry("letters_black", "a89ab5"),
            Map.entry("error", "a33b53"),
            Map.entry("success", "8fffc1"),
            Map.entry("success_light", "a1ffc3"),
            Map.entry("warning", "ff9500"),
            Map.entry("warning_light", "ffd2a8"),
            Map.entry("info", "59a4ff"),
            Map.entry("info_light", "7db7ff"),
            Map.entry("accent", "ff6b9d"),
            Map.entry("neutral", "6c757d"),
            Map.entry("highlight", "ffd700"),
            Map.entry("muted", "868e96"));

    private static final String LEGACY = "000000 0000aa 00aa00 00aaaa aa0000 aa00aa ffaa00 aaaaaa 555555 5555ff 55ff55 55ffff ff5555 ff55ff ffff55 ffffff";
    private static final int CENTER_PX = 154;

    private Text() {
    }

    // ------------------------------------------------------------------ lines

    /** A configured line: its leading {@code [..]} block, then the text, centred when asked. */
    public static @NotNull Line line(@NotNull String raw) {
        Sound sound = null;
        boolean center = false;
        String body = raw;
        if (body.startsWith("[")) {
            int end = body.indexOf(']');
            String block = end > 0 ? body.substring(1, end) : "";
            boolean directives = !block.isEmpty();
            for (String part : block.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.equals("center") && !trimmed.startsWith("sound:")) {
                    directives = false;
                }
            }
            if (directives) {
                for (String part : block.split(";")) {
                    String trimmed = part.trim();
                    if (trimmed.equals("center")) {
                        center = true;
                    } else {
                        sound = sound(trimmed.substring("sound:".length()));
                    }
                }
                body = body.substring(end + 1);
            }
        }
        List<Span> spans = spans(body);
        if (center) {
            spans = center(spans);
        }
        return new Line(spans, sound);
    }

    private static @Nullable Sound sound(String spec) {
        String[] parts = spec.split("\\|");
        if (parts[0].isBlank()) {
            return null;
        }
        try {
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            return new Sound(parts[0].trim().toLowerCase(Locale.ROOT), volume, pitch);
        } catch (NumberFormatException malformed) {
            return new Sound(parts[0].trim().toLowerCase(Locale.ROOT), 1f, 1f);
        }
    }

    // ---------------------------------------------------------------- parsing

    public static @NotNull List<Span> spans(@NotNull String text) {
        return new Parser(text).parse();
    }

    private static final class Parser {
        private final String in;
        private final List<Span> out = new ArrayList<>();
        private final StringBuilder buffer = new StringBuilder();
        private String color;
        private int decorations;
        private Click click;
        private List<Span> hover;

        Parser(String in) {
            this.in = in;
        }

        List<Span> parse() {
            int i = 0;
            while (i < in.length()) {
                char c = in.charAt(i);
                if (c == '\\' && i + 1 < in.length()) {
                    buffer.append(in.charAt(i + 1));
                    i += 2;
                } else if ((c == '&' || c == '§') && i + 1 < in.length()) {
                    i = legacy(i);
                } else if (c == '{') {
                    i = token(i);
                } else if (c == '<') {
                    i = tag(i);
                } else {
                    buffer.append(c);
                    i++;
                }
            }
            flush();
            return out;
        }

        private int legacy(int i) {
            char code = Character.toLowerCase(in.charAt(i + 1));
            if (code == '#' && i + 8 <= in.length() && hex(in.substring(i + 2, i + 8))) {
                color(in.substring(i + 2, i + 8));
                return i + 8;
            }
            int index = "0123456789abcdef".indexOf(code);
            if (index >= 0) {
                color(LEGACY.substring(index * 7, index * 7 + 6));
            } else if ("klmno".indexOf(code) >= 0) {
                flush();
                decorations |= switch (code) {
                    case 'k' -> OBFUSCATED;
                    case 'l' -> BOLD;
                    case 'm' -> STRIKETHROUGH;
                    case 'n' -> UNDERLINED;
                    default -> ITALIC;
                };
            } else if (code == 'r') {
                flush();
                color = null;
                decorations = 0;
            } else {
                buffer.append(in.charAt(i));
                return i + 1;
            }
            return i + 2;
        }

        private int token(int i) {
            int end = in.indexOf('}', i);
            String hex = end > 0 ? PALETTE.get(in.substring(i + 1, end)) : null;
            if (hex == null) {
                buffer.append('{');
                return i + 1;
            }
            color(hex);
            return end + 1;
        }

        private int tag(int i) {
            String rest = in.substring(i);
            if (rest.startsWith("<hover:show_text:'")) {
                int end = in.indexOf("'>", i);
                if (end > 0) {
                    flush();
                    hover = spans(in.substring(i + "<hover:show_text:'".length(), end));
                    return end + 2;
                }
            }
            int end = in.indexOf('>', i);
            if (end < 0) {
                buffer.append('<');
                return i + 1;
            }
            String tag = in.substring(i + 1, end);
            if (tag.startsWith("#") && tag.length() == 7 && hex(tag.substring(1))) {
                color(tag.substring(1));
            } else if (tag.equals("reset")) {
                flush();
                color = null;
                decorations = 0;
            } else if (tag.startsWith("click:") && tag.indexOf(':', 6) > 6) {
                flush();
                int colon = tag.indexOf(':', 6);
                click = new Click(tag.substring(6, colon).toLowerCase(Locale.ROOT), tag.substring(colon + 1));
            } else if (tag.equals("/click")) {
                flush();
                click = null;
            } else if (tag.equals("/hover")) {
                flush();
                hover = null;
            } else {
                buffer.append('<');
                return i + 1;
            }
            return end + 1;
        }

        private void color(String hex) {
            flush();
            color = hex.toLowerCase(Locale.ROOT);
            decorations = 0;
        }

        private void flush() {
            if (!buffer.isEmpty()) {
                out.add(new Span(buffer.toString(), color, decorations, click, hover));
                buffer.setLength(0);
            }
        }

        private static boolean hex(String value) {
            return value.length() == 6 && value.chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Text from outside (a player or network name) that must show exactly as it is. */
    public static @NotNull String escape(@Nullable String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '&' || c == '§' || c == '<' || c == '{') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    public static @NotNull String plain(@NotNull List<Span> spans) {
        StringBuilder out = new StringBuilder();
        spans.forEach(span -> out.append(span.text()));
        return out.toString();
    }

    /** Section-sign codes (hex as {@code §x§r§r§g§g§b§b}), for consoles. */
    public static @NotNull String legacy(@NotNull List<Span> spans) {
        StringBuilder out = new StringBuilder();
        for (Span span : spans) {
            out.append("§r");
            if (span.color() != null) {
                out.append("§x");
                for (char c : span.color().toCharArray()) {
                    out.append('§').append(c);
                }
            }
            if (span.has(BOLD)) out.append("§l");
            if (span.has(ITALIC)) out.append("§o");
            if (span.has(UNDERLINED)) out.append("§n");
            if (span.has(STRIKETHROUGH)) out.append("§m");
            if (span.has(OBFUSCATED)) out.append("§k");
            out.append(span.text());
        }
        return out.toString();
    }

    /** Spaces in front so the line sits in the middle of the default chat width. */
    static List<Span> center(List<Span> spans) {
        int width = 0;
        for (Span span : spans) {
            for (char c : span.text().toCharArray()) {
                width += width(c) + (span.has(BOLD) && c != ' ' ? 1 : 0) + 1;
            }
        }
        int pad = (CENTER_PX - width / 2) / 4;
        if (pad <= 0) {
            return spans;
        }
        List<Span> centred = new ArrayList<>(spans.size() + 1);
        centred.add(new Span(" ".repeat(pad), null, 0, null, null));
        centred.addAll(spans);
        return centred;
    }

    private static int width(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 1;
            case 'l', '`' -> 2;
            case 'I', '[', ']', 't', ' ' -> 3;
            case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 4;
            case '@', '~' -> 6;
            default -> 5;
        };
    }
}
