package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the phrase pass (suffix array + LCP, Technical Specification 3.2.3) and session continuation. */
class PhraseCompressionTest {

    private static final CompressionSettings PHRASES_ON =
            new CompressionSettings(3, 2, 3, 0, false);

    @Test
    void repeatedPhraseGetsCoded() {
        String text = "это длинная фраза которая повторяется в тексте. "
                + "это длинная фраза которая повторяется в тексте. финал";
        Compressor c = new Compressor(PHRASES_ON);
        Compressor.Result r = c.compress(text);
        Decompressor d = new Decompressor(r.dictionary());
        assertEquals(text, d.decompress(r.compressedText()), "round-trip must hold with phrases");
        assertTrue(r.compressedText().length() < text.length(),
                "phrase coding must shorten the text");
        boolean hasPhrase = r.dictionary().entries().stream()
                .anyMatch(e -> e.value().tokens().get(0).text().contains(" "));
        assertTrue(hasPhrase, "dictionary must contain a multi-word phrase entry");
    }

    @Test
    void phrasePassDisabledWhenMinPhraseLengthHigh() {
        String text = "abc def abc def abc def";
        // minPhraseLength = 7 tokens: no phrase of that size exists -> words-only behaviour
        Compressor c = new Compressor(new CompressionSettings(3, 2, 7, 0, false));
        Compressor.Result r = c.compress(text);
        assertEquals(text, new Decompressor(r.dictionary()).decompress(r.compressedText()));
        assertTrue(r.dictionary().entries().stream()
                .noneMatch(e -> e.value().tokens().get(0).text().contains(" ")));
    }

    @Test
    void compressedNeverLongerThanInput() {
        String[] samples = {
            "aa bb cc aa bb cc",
            "слово слово слово",
            "one two one two one two one two",
            "x y z",
            "абв гд еж абв гд еж абв гд еж абв гд еж",
        };
        for (String s : samples) {
            Compressor c = new Compressor(PHRASES_ON);
            Compressor.Result r = c.compress(s);
            assertTrue(r.compressedText().length() <= s.length(),
                    "output longer than input for: " + s);
            assertEquals(s, new Decompressor(r.dictionary()).decompress(r.compressedText()));
        }
    }

    @Test
    void sessionContinuationReusesBaselineCodes() throws Exception {
        String msg1 = "программа сжимает текст для чата эффективно программа сжимает текст";
        String msg2 = "программа сжимает текст снова и ещё раз программа сжимает текст полностью";
        Compressor c = new Compressor(PHRASES_ON);
        Compressor.Result r1 = c.compress(msg1);
        // Simulate JSON persistence between messages
        DictionaryJsonSerializer ser = new DictionaryJsonSerializer();
        String json = ser.toJson(new DictionaryJsonSerializer.Root("ID1234", java.time.Instant.now(), r1.dictionary()));
        Dictionary baseline = ser.fromJson(json).dictionary();

        Compressor.Result r2 = c.compress(msg2, baseline, CompressionProgress.NONE);
        Decompressor d = new Decompressor(r2.dictionary());
        assertEquals(msg2, d.decompress(r2.compressedText()), "session round-trip must hold");
        // Baseline codes must be preserved unchanged
        for (DictEntry be : baseline.entries()) {
            DictEntry now = r2.dictionary().get(be.code());
            assertNotNull(now, "baseline code must survive: " + be.code());
            assertEquals(be.value().tokens().get(0).text(), now.value().tokens().get(0).text());
        }
    }

    @Test
    void interruptionProducesPartialResult() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("многословное повторение текста многословное повторение текста ").append(i % 97).append(' ');
        }
        String text = sb.toString();
        Compressor c = new Compressor(PHRASES_ON);
        CompressionProgress alwaysCancelled = new CompressionProgress() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void update(String phase, int percentDone) {
            }
        };
        Compressor.CompressionInterruptedException ex = assertThrows(
                Compressor.CompressionInterruptedException.class,
                () -> c.compress(text, new Dictionary(), alwaysCancelled));
        Compressor.Result partial = ex.partialResult();
        assertNotNull(partial.compressedText());
        // Partial output must still decompress back to something (no crash) using its own dictionary
        String back = new Decompressor(partial.dictionary()).decompress(partial.compressedText());
        assertNotNull(back);
    }
}
