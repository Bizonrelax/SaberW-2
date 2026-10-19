package com.saberw.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TZ 2026-10-19 step 2: all six about texts must load, be non-empty,
 * mention "SaberW 2" and contain the releases line; unknown language falls back to ru.
 */
class AboutTextsTest {

    @Test
    void allSixLanguagesLoadWithExpectedContent() {
        for (String lang : AboutTexts.LANGUAGES) {
            String text = AboutTexts.load(lang);
            assertFalse(text.isBlank(), "text for " + lang + " must not be empty");
            assertTrue(text.contains("SaberW 2"), "text for " + lang + " must mention SaberW 2");
            assertTrue(text.contains("Releases (Latest)"),
                    "text for " + lang + " must contain the releases line");
            assertTrue(text.contains("https://github.com/Bizonrelax/SaberW-2"),
                    "text for " + lang + " must contain the repository link");
        }
    }

    @Test
    void unknownLanguageFallsBackToRussian() {
        assertEquals(AboutTexts.load("ru"), AboutTexts.load("xx"));
        assertEquals(AboutTexts.load("ru"), AboutTexts.load(null));
    }

    @Test
    void russianTextMatchesFirstLineOfTheSpec() {
        String ru = AboutTexts.load("ru");
        assertTrue(ru.startsWith("SaberW 2 — программа сжатия текста для переписки с нейросетями."),
                "russian text must start with the exact sentence from the TZ");
    }
}
