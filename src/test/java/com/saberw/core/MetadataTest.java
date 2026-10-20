package com.saberw.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.DictionaryMetadata;
import com.saberw.model.EntryValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metadata model + serializer tests (TZ 6, 17.1). */
class MetadataTest {

    private static Dictionary smallDictionary() {
        Dictionary d = new Dictionary();
        d.put(new DictEntry("㐀", new EntryValue(List.of(EntryValue.ValueToken.text("привет"))), 1, 3, 3, "2026-01-01T00:00:00Z", false));
        d.put(new DictEntry("㐁", new EntryValue(List.of(EntryValue.ValueToken.text("мир"))), 1, 2, 2, "2026-01-01T00:00:00Z", true));
        return d;
    }

    private static DictionaryMetadata richMetadata() {
        DictionaryMetadata m = new DictionaryMetadata(
                "тестовая заметка",
                List.of("https://chat.example/1", "https://chat.example/2"),
                new ArrayList<>());
        m = m.withCompression("2026-10-01")   // totalCompressions=1, appliedDates gains 2026-10-01
             .withCompression("2026-10-15")   // totalCompressions=2, appliedDates gains 2026-10-15
             .withDecompression()             // totalDecompressions=1
             .withDecompression()             // totalDecompressions=2
             .withSupplement()                // supplementCount=1
             .withMerge()                     // mergeCount=1
             .withJsonExport(true);           // exportCount=1, lastExportIncludedMetadata=true
        return m;
    }

    @Test
    void roundTripPreservesAllCountersAndLists() throws Exception {
        Dictionary dict = smallDictionary();
        DictionaryMetadata meta = richMetadata().recompute(dict);
        DictionaryJsonSerializer.Root root = new DictionaryJsonSerializer.Root(
                "ID1234", Instant.EPOCH, dict, CompressionSettings.maxCjk(), meta);
        DictionaryJsonSerializer ser = new DictionaryJsonSerializer();
        DictionaryMetadata back = ser.fromJson(ser.toJson(root)).metadata();

        assertEquals(meta.note(), back.note());
        assertEquals(meta.chatLinks(), back.chatLinks());
        assertEquals(meta.appliedDates(), back.appliedDates());
        assertEquals(meta.totalCompressions(), back.totalCompressions());
        assertEquals(meta.totalDecompressions(), back.totalDecompressions());
        assertEquals(meta.supplementCount(), back.supplementCount());
        assertEquals(meta.mergeCount(), back.mergeCount());
        assertEquals(meta.exportCount(), back.exportCount());
        assertEquals(meta.lastExportIncludedMetadata(), back.lastExportIncludedMetadata());
        assertEquals(meta.entriesCount(), back.entriesCount());
        assertEquals(meta.totalValueChars(), back.totalValueChars());
        // spot-check the real values survived (not stubs)
        assertEquals(2, back.totalCompressions());
        assertEquals(2, back.totalDecompressions());
        assertEquals(1, back.supplementCount());
        assertEquals(1, back.mergeCount());
        assertEquals(1, back.exportCount());
        assertTrue(back.lastExportIncludedMetadata());
        assertEquals(List.of("2026-10-01", "2026-10-15"), back.appliedDates());
        assertEquals(2, back.chatLinks().size());
    }

    @Test
    void sizeBytesMatchesIndependentRecountPerTz610() throws Exception {
        Dictionary dict = smallDictionary();
        DictionaryMetadata meta = richMetadata().recompute(dict);
        DictionaryJsonSerializer.Root root = new DictionaryJsonSerializer.Root(
                "ID1234", Instant.EPOCH, dict, CompressionSettings.maxCjk(), meta);
        DictionaryJsonSerializer ser = new DictionaryJsonSerializer();
        String json = ser.toJson(root);

        long reported = Long.parseLong(new ObjectMapper()
                .readTree(json).path("metadata").path("sizeBytes").asText());
        assertTrue(reported > 0, "sizeBytes must be a positive estimate");

        // Independent recount per TZ 6.10: take the produced JSON, set metadata.sizeBytes=0,
        // measure UTF-8 byte length of that variant — it must equal the reported value.
        JsonNode node = new ObjectMapper().readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("metadata")).put("sizeBytes", 0);
        String measuredJson = new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(node).replace("\r\n", "\n");
        long measured = measuredJson.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(measured, reported);
    }

    @Test
    void withCompressionTwiceSameDayKeepsSingleDateAndCountsTwo() {
        DictionaryMetadata m = new DictionaryMetadata()
                .withCompression("2026-10-17")
                .withCompression("2026-10-17");
        assertEquals(2, m.totalCompressions());
        assertEquals(List.of("2026-10-17"), m.appliedDates());
    }

    @Test
    void withBotExportBumpsExportCountOnly() {
        DictionaryMetadata base = new DictionaryMetadata().withJsonExport(true);
        DictionaryMetadata after = base.withBotExport();
        assertEquals(base.exportCount() + 1, after.exportCount());
        assertEquals(base.lastExportIncludedMetadata(), after.lastExportIncludedMetadata());
        // and the other way round: bot export over a false flag keeps it false
        DictionaryMetadata falseBase = new DictionaryMetadata().withJsonExport(false);
        DictionaryMetadata afterFalse = falseBase.withBotExport();
        assertFalse(afterFalse.lastExportIncludedMetadata());
        assertEquals(2, afterFalse.exportCount());
    }

    @Test
    void missingMetadataBlockFallsBackToStubWithRecompute() throws Exception {
        // hand-written legacy file without any metadata block
        String legacyJson = """
                {
                  "format": "saberw-dictionary",
                  "formatVersion": 1,
                  "id": "LEGACY",
                  "createdAt": "1970-01-01T00:00:00Z",
                  "dictionary": {
                    "updatedAt": "1970-01-01T00:00:00Z",
                    "measureUnit": "characters",
                    "entries": [
                      {"code": "\\u3400", "value": [{"type": "text", "text": "привет"}],
                       "level": 1, "frequency": 3, "codeAppliedCount": 3,
                       "addedAt": "2026-01-01T00:00:00Z", "sentToBot": false}
                    ]
                  }
                }
                """;
        DictionaryJsonSerializer.Root loaded = new DictionaryJsonSerializer().fromJson(legacyJson);
        DictionaryMetadata m = loaded.metadata();
        assertEquals(0, m.totalCompressions());
        assertEquals(0, m.exportCount());
        assertEquals("", m.note());
        assertTrue(m.appliedDates().isEmpty());
        // recompute happened against the dictionary
        assertEquals(loaded.dictionary().size(), m.entriesCount());
        int chars = 0;
        for (DictEntry e : loaded.dictionary().entries()) {
            chars += e.value().charLength();
        }
        assertEquals(chars, m.totalValueChars());
    }
}
