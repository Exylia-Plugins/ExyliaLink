package net.exylia.link.text;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@link Text.Span}s as BungeeCord chat components, the rich text Spigot, Paper and BungeeCord all
 * accept ({@code player.spigot().sendMessage(...)}, {@code ProxiedPlayer#sendMessage}). Only API
 * present since bungeecord-chat 1.16 (the one Spigot 1.17 ships) is used.
 */
public final class BungeeChat {

    private BungeeChat() {
    }

    public static @NotNull BaseComponent[] components(@NotNull List<Text.Span> spans) {
        if (spans.isEmpty()) {
            return new BaseComponent[]{new TextComponent("")};
        }
        BaseComponent[] out = new BaseComponent[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            out[i] = component(spans.get(i));
        }
        return out;
    }

    private static BaseComponent component(Text.Span span) {
        TextComponent component = new TextComponent(span.text());
        component.setColor(span.color() == null ? ChatColor.WHITE : ChatColor.of("#" + span.color()));
        component.setBold(span.has(Text.BOLD));
        component.setItalic(span.has(Text.ITALIC));
        component.setUnderlined(span.has(Text.UNDERLINED));
        component.setStrikethrough(span.has(Text.STRIKETHROUGH));
        component.setObfuscated(span.has(Text.OBFUSCATED));
        if (span.click() != null) {
            try {
                component.setClickEvent(new ClickEvent(ClickEvent.Action.valueOf(span.click().action().toUpperCase(Locale.ROOT)),
                        span.click().value()));
            } catch (IllegalArgumentException unknownAction) {
                // Shown without the click rather than not at all.
            }
        }
        if (span.hover() != null) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(components(span.hover()))));
        }
        return component;
    }
}
