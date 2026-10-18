package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.EntryValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for "For bot" export: full-value serialization with escaping (TZ 10.2) and separator guard (TZ 18.6). */
class BotExporterTest {

    private static DictEntry entry(String code, EntryValue.ValueToken... tokens) {
        return new DictEntry(code, new EntryValue(List.of(tokens)), 1, 1, 1, "2026-01-01T00:00:00Z", false);
    }

    @Test
    void multiTokenValueIsConcatenatedAndEscaped() {
        Dictionary dict = new Dictionary();
        dict.put(entry("㐀",
                EntryValue.ValueToken.text("первая "),
                EntryValue.ValueToken.codeRef("㐁"),
                EntryValue.ValueToken.text(" третья")));
        String out = BotExporter.build(dict, true, false, "сжатое сообщение");

        assertTrue(out.contains("㐀\tпервая 㐁 третья\n"),
                "value must be the concatenation of ALL tokens on one line, got:\n" + out);

        // Escaping: backslash doubles, tab/newline/CR become \t \n \r sequences.
        String serialized = BotExporter.serializeValue(new EntryValue(List.of(
                EntryValue.ValueToken.text("a\\b\nc\td"))));
        assertEquals("a\\\\b\\nc\\td", serialized);
        String serializedCr = BotExporter.serializeValue(new EntryValue(List.of(
                EntryValue.ValueToken.text("x\ry"))));
        assertEquals("x\\ry", serializedCr);

        // The escaped value lands in the export as a single physical line.
        Dictionary dict2 = new Dictionary();
        dict2.put(entry("㐀", EntryValue.ValueToken.text("строка1\nстрока2\tконец\\")));
        String out2 = BotExporter.build(dict2, true, false, "текст");
        assertTrue(out2.contains("㐀\tстрока1\\nстрока2\\tконец\\\\\n"),
                "newlines/tabs/backslashes must be escaped in export, got:\n" + out2);
    }

    @Test
    void separatorFallsBackToTildesWhenUnderscoresCollide() {
        Dictionary dict = new Dictionary();
        dict.put(entry("㐀", EntryValue.ValueToken.text("значение___внутри")));
        String out = BotExporter.build(dict, true, false, "после словаря ___ идёт текст");
        assertFalse(out.contains("\n___\n"), "colliding '___' separator must not be used:\n" + out);
        assertTrue(out.contains("\n~~~\n"), "separator must fall back to three tildes:\n" + out);
    }

    @Test
    void allCandidatesCollidingThrowsIllegalState() {
        Dictionary dict = new Dictionary();
        dict.put(entry("㐀", EntryValue.ValueToken.text("___ ~~~ ### === ***")));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BotExporter.build(dict, true, false, "текст"));
        assertEquals("All block separators collide", ex.getMessage());
    }
}
