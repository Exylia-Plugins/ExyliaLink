package net.exylia.link.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    @Test
    void coloursDecorationsAndResets() {
        List<Text.Span> spans = Text.spans("<#8a51c4>&lEXYLIA {letters_black}•<reset> &atext");
        assertEquals("EXYLIA ", spans.get(0).text());
        assertEquals("8a51c4", spans.get(0).color());
        assertTrue(spans.get(0).has(Text.BOLD));
        assertEquals("a89ab5", spans.get(1).color());
        assertFalse(spans.get(1).has(Text.BOLD), "a colour clears bold, as a legacy code does");
        assertNull(spans.get(2).color());
        assertEquals("55ff55", spans.get(3).color());
        assertEquals("EXYLIA • text", Text.plain(spans));
    }

    @Test
    void clickAndHoverScopes() {
        List<Text.Span> spans = Text.spans("a <click:open_url:https://x.y/z?a=1><hover:show_text:'<#e7cfff>Open'>link</hover></click> b");
        Text.Span link = spans.get(1);
        assertEquals("link", link.text());
        assertEquals("open_url", link.click().action());
        assertEquals("https://x.y/z?a=1", link.click().value());
        assertNotNull(link.hover());
        assertEquals("e7cfff", link.hover().get(0).color());
        assertNull(spans.get(2).click());
        assertNull(spans.get(2).hover());
    }

    @Test
    void escapedTextIsNeverFormatting() {
        String name = "<click:run_command:/op me>&lBob{primary}\\";
        assertEquals(name, Text.plain(Text.spans(Text.escape(name))));
        assertEquals(name, Values.of().putText("n", name).apply("%n%").transform(Text::spans).stream()
                .map(Text.Span::text).reduce("", String::concat));
    }

    @Test
    void leadingDirectives() {
        Text.Line line = Text.line("[sound:entity.villager.no|0.6|1.0;center]Hello");
        assertEquals("entity.villager.no", line.sound().key());
        assertEquals(0.6f, line.sound().volume());
        assertTrue(line.spans().get(0).text().isBlank(), "centred with leading spaces");
        assertEquals("[not a directive] hi", Text.line("[not a directive] hi").plain());
    }

    @Test
    void everyBundledMessageParses() {
        Messages messages = Messages.bundled();
        assertFalse(messages.lines("link.code", Values.of("code", "ACD347").put("url", "https://l").put("time", "5m")).isEmpty());
        assertTrue(messages.lines("admin.connect-done", Values.of()).get(0).plain().contains("EXYLIA LINK"));
        assertTrue(messages.lines("missing.key", Values.of()).isEmpty());
    }
}
