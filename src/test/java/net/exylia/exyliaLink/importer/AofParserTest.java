package net.exylia.exyliaLink.importer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AofParserTest {

    private static final String A = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String B = "853c80ef-3c37-49fd-aa49-938b674adae6";
    private static final String C = "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2";

    @Test
    void replaysLinksAndUnlinksInOrder() {
        String aof = String.join("\n",
                "111111111111111111 " + A,
                "222222222222222222 " + B,
                "-111111111111111111",                 // unlink by Discord id
                "333333333333333333 " + C,
                "-" + C,                               // unlink by UUID
                "444444444444444444 " + A,
                "-555555555555555555 " + B,           // the Discord id is unknown, the UUID still goes
                "not a line",
                "");
        assertEquals(Map.of("444444444444444444", A), AofParser.parse(aof));
    }

    @Test
    void aNewLinkReplacesBothSides() {
        String aof = String.join("\n",
                "111111111111111111 " + A,
                "111111111111111111 " + B,            // the same Discord account moves to B
                "222222222222222222 " + B);           // and B moves to another Discord account
        assertEquals(Map.of("222222222222222222", B), AofParser.parse(aof));
    }

    @Test
    void trailingTextAndWindowsLineEndingsAreAccepted() {
        assertEquals(Map.of("111111111111111111", A), AofParser.parse("111111111111111111 " + A + " extra\r\n"));
    }
}
