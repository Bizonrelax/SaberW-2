package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comment masking tests (Technical Specification 3.2.1, 12.4).
 * Active tests match the current main implementation (unconditional masking,
 * "//" and block comments only). Disabled tests document deviations from 12.2.
 */
class CompressorMaskedTest {

    private static final CompressionSettings SETTINGS = CompressionSettings.maxCjk();

    private static boolean hasValue(Dictionary d, String raw) {
        for (DictEntry e : d.entries()) {
            if (e.value().tokens().get(0).text().equals(raw)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void lineCommentWordsAreExcludedFromFrequencies() {
        Compressor.Result r = new Compressor(SETTINGS).compress("alpha alpha // beta beta beta");
        assertTrue(hasValue(r.dictionary(), "alpha"));
        assertFalse(hasValue(r.dictionary(), "beta"));
    }

    @Test
    void blockCommentWordsAreExcludedFromFrequencies() {
        Compressor.Result r = new Compressor(SETTINGS)
                .compress("gamma gamma gamma /* delta delta delta */ gamma");
        assertTrue(hasValue(r.dictionary(), "gamma"));
        assertFalse(hasValue(r.dictionary(), "delta"));
    }

    @Test
    void roundTripPreservesCommentsAndEscapes() {
        String text = "round trip § and 㐀 check // masked mask masked\n tail tail tail";
        Compressor.Result r = new Compressor(SETTINGS).compress(text);
        assertEquals(text, new Decompressor(r.dictionary()).decompress(r.compressedText()));
    }

    @Test
    @Disabled("Отклонение от ТЗ 12.2: '://' не должен начинать комментарий. Включить после защиты URL.")
    void urlIsNotCommentPerSpec() {
        Compressor.Result r = new Compressor(SETTINGS).compress("http://zoom zoom zoom zoom");
        assertTrue(hasValue(r.dictionary(), "zoom"));
    }

    @Test
    @Disabled("Отклонение от ТЗ 12.2: нет флага ignoreComments (маска всегда включена). Включить после добав флага.")
    void maskingCanBeTurnedOffPerSpec() {
        // Будущее: CompressionSettings с ignoreComments=false должна считать слова в комментариях.
    }

    @Test
    @Disabled("Отклонение от ТЗ 12.2: правила HTML и Python не реализованы. Включить после реализации.")
    void htmlCommentsMaskedPerSpec() {
        Compressor.Result r = new Compressor(SETTINGS).compress("<!-- omega omega omega -->");
        assertFalse(hasValue(r.dictionary(), "omega"));
    }
}