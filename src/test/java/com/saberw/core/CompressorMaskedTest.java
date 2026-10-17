package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;

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
    /** Masking is active only when the ignoreComments option is turned on (TZ 3.2.1). */
    private static final CompressionSettings MASK_ON = CompressionSettings.maxCjk().withIgnoreComments(true);

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
        Compressor.Result r = new Compressor(MASK_ON).compress("alpha alpha // beta beta beta");
        assertTrue(hasValue(r.dictionary(), "alpha"));
        assertFalse(hasValue(r.dictionary(), "beta"));
    }

    @Test
    void blockCommentWordsAreExcludedFromFrequencies() {
        Compressor.Result r = new Compressor(MASK_ON)
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
    void urlIsNotCommentPerSpec() {
        Compressor.Result r = new Compressor(SETTINGS).compress("http://zoom zoom zoom zoom");
        assertTrue(hasValue(r.dictionary(), "zoom"));
    }

    @Test
    void maskingCanBeTurnedOffPerSpec() {
        String text = "alpha alpha // beta beta beta";
        Compressor.Result off = new Compressor(SETTINGS.withIgnoreComments(false)).compress(text);
        assertTrue(hasValue(off.dictionary(), "beta"), "with ignoreComments=false comment words must be counted");
        Compressor.Result on = new Compressor(SETTINGS.withIgnoreComments(true)).compress(text);
        assertFalse(hasValue(on.dictionary(), "beta"));
    }

    @Test
    void htmlCommentsMaskedPerSpec() {
        Compressor.Result r = new Compressor(MASK_ON).compress("<!-- omega omega omega -->");
        assertFalse(hasValue(r.dictionary(), "omega"));
    }
}