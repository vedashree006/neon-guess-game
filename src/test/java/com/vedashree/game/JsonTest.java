package com.vedashree.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"line1\\nline2\\t\"", Json.write("line1\nline2\t"));
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void escapesHtmlSensitiveAndLineSeparatorCharacters() {
        assertEquals("\"\\u003cscript\\u003e\"", Json.write("<script>"));
        assertEquals("\"a\\u0026b\"", Json.write("a&b"));
        assertEquals("\"\\u2028\"", Json.write("\u2028"));
    }

    @Test
    void writesPrimitivesAndNull() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(true));
        assertEquals("42", Json.write(42));
        assertEquals("\"TOO_HIGH\"", Json.write(GameLogic.Hint.TOO_HIGH));
        assertEquals("null", Json.write(Double.NaN));
    }

    @Test
    void writesNestedStructures() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", List.of(true, "x"));
        map.put("c", null);
        map.put("d", Map.of("k", "v"));
        assertEquals("{\"a\":1,\"b\":[true,\"x\"],\"c\":null,\"d\":{\"k\":\"v\"}}", Json.write(map));
    }

    @Test
    void keysAreEscapedToo() {
        assertEquals("{\"a\\\"b\":1}", Json.write(Map.of("a\"b", 1)));
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    }
}
