package com.saberw.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip invariant tests: decompress(compress(text)) == text (with case normalization off). */
class RoundTripTest {

    private static String roundTrip(String text, CompressionSettings settings) {
        Compressor compressor = new Compressor(settings);
        Compressor.Result result = compressor.compress(text);
        Decompressor decompressor = new Decompressor(result.dictionary());
        return decompressor.decompress(result.compressedText());
    }

    private static final CompressionSettings NO_NORMALIZE =
            new CompressionSettings(3, 2, 4, 0, false);

    @Test
    void russianText() {
        String text = "Программа сжимает текст. Программа сжимает текст эффективно, "
                + "программа распаковывает текст обратно.";
        assertEquals(text.toLowerCase(), roundTrip(text,
                new CompressionSettings(3, 2, 4, 0, true)));
    }

    @Test
    void englishTextNoNormalization() {
        String text = "compression compression compression decompression decompression "
                + "dictionary dictionary dictionary";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void javaCodeSnippet() {
        String text = "public static void main public static void main args length "
                + "public static void main System.out.println";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void emojiText() {
        String text = "привет 👨‍👩‍👧‍👦 привет 😀😀😀 тест тест тест эмодзи 🇷🇺 эмодзи";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void literalSectionSign() {
        String text = "раздел § раздел § ещё раздел §§§ конец конец конец";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void literalCjkExtensionA() {
        // 㐀 (U+3400) and 㐁 (U+3401) are code-range characters appearing literally
        String text = "код 㐀 символ 㐁 код 㐀 символ 㐁 повтор повтор повтор";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void literalEscapeSequenceInSource() {
        String text = "подозрительная последовательность §#㐄 внутри текста §#㐄 §#㐄";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void mixedText() {
        String text = "Смешанный текст mixed words 12345 !!! ??? ... смещ smesh smesh smesh";
        assertEquals(text, roundTrip(text, NO_NORMALIZE));
    }

    @Test
    void emptyAndShortTexts() {
        assertEquals("", roundTrip("", NO_NORMALIZE));
        assertEquals("а б в", roundTrip("а б в", NO_NORMALIZE));
    }

    @Test
    void noRawCodeCollidesWithShieldedText() {
        // After shielding, no CJK Ext A character appears unescaped in the prepared text
        String shielded = Shielder.shield("текст § 㐀 㐁 текст");
        for (int i = 0; i < shielded.length(); i++) {
            if (Shielder.isCjkExtensionA(shielded.charAt(i))) {
                assertTrue(i >= 2 && shielded.charAt(i - 2) == '§' && shielded.charAt(i - 1) == '#',
                        "Unescaped CJK Ext A at index " + i);
            }
        }
    }

    @Test
    void savingsFormulas() {
        // S_new = (F-1)*L - F*C - C ; F=3, L=10, C=1 -> 2*10 - 3 - 1 = 16
        assertEquals(16, Compressor.savingsNew(3, 10, 1));
        // F=2, L=3, C=1 -> 1*3 - 2 - 1 = 0
        assertEquals(0, Compressor.savingsNew(2, 3, 1));
        // S_exist = F*(L-C) ; F=5, L=8, C=1 -> 35
        assertEquals(35, Compressor.savingsExist(5, 8, 1));
    }

    @Test
    void shieldUnshieldRoundTrip() {
        String original = "§ # 㐇 §#㐈 §§ test §# notescape";
        assertEquals(original, Shielder.unshield(Shielder.shield(original)));
    }

    @Test
    void tokenizerKeepsEscapeAtomsAtomic() {
        String shielded = Shielder.shield("a § b");
        var tokens = SaberTokenizer.tokenize(shielded);
        boolean foundAtom = tokens.stream()
                .anyMatch(t -> t.type() == SaberTokenizer.TokenType.ESCAPE_ATOM && t.text().equals("§§"));
        assertTrue(foundAtom, "Expected atomic escape token §§");
    }

    @Test
    void dictionaryIdIsAlphanumericSixChars() {
        DictionaryIdGenerator gen = new DictionaryIdGenerator();
        String id = gen.next(x -> true);
        assertEquals(6, id.length());
        assertTrue(id.matches("[A-Za-z0-9]{6}"), "Bad ID: " + id);
    }
}
