package com.saberw.core;
import com.saberw.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
class DbgTest {
    @Test void dbg() throws Exception {
        Dictionary d = new Dictionary();
        d.put(new DictEntry("㐀", new EntryValue(List.of(EntryValue.ValueToken.text("привет"))), 1, 3, 3, "2026-01-01T00:00:00Z", false));
        d.put(new DictEntry("㐁", new EntryValue(List.of(EntryValue.ValueToken.text("мир"))), 1, 2, 2, "2026-01-01T00:00:00Z", true));
        DictionaryMetadata m = new DictionaryMetadata("тестовая заметка", List.of("https://chat.example/1","https://chat.example/2"), new ArrayList<>())
            .withCompression("2026-10-01").withCompression("2026-10-15").withDecompression().withDecompression().withSupplement().withMerge().withJsonExport(true).recompute(d);
        var root = new DictionaryJsonSerializer.Root("ID1234", Instant.EPOCH, d, CompressionSettings.maxCjk(), m);
        var ser = new DictionaryJsonSerializer();
        String json = ser.toJson(root, Instant.EPOCH);
        long reported = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("metadata").path("sizeBytes").asLong();
        System.out.println("DBG reported=" + reported + " finalLen=" + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        DictionaryMetadata z = new DictionaryMetadata(m); z.setSizeBytes(0);
        var zr = new DictionaryJsonSerializer.Root("ID1234", Instant.EPOCH, d, CompressionSettings.maxCjk(), z);
        System.out.println("DBG measureSizeBytes=" + ser.measureSizeBytes(zr));
        // also zero-size raw length via reflection-free path: serialize zero root with fixed instant
        String zjson = ser.toJson(zr, Instant.EPOCH);
        long zreported = new com.fasterxml.jackson.databind.ObjectMapper().readTree(zjson).path("metadata").path("sizeBytes").asLong();
        System.out.println("DBG toJson(zeroRoot) reported=" + zreported + " len=" + zjson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
