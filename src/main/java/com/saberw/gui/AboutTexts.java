package com.saberw.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loader for the "About program" texts (TZ 2026-10-19, step 2).
 * Reads resources/about/about.&lt;lang&gt;.txt from the classpath as UTF-8.
 * An unknown language falls back to Russian.
 */
public final class AboutTexts {

    /** Languages shipped with the application. */
    public static final String[] LANGUAGES = {"ru", "en", "zh", "fr", "es", "pt"};

    private static final String FALLBACK_LANGUAGE = "ru";

    private AboutTexts() {
    }

    /**
     * Returns the about text for the given language code.
     * Unknown or null language falls back to the Russian text.
     *
     * @throws IllegalStateException if the bundled resource is missing or unreadable
     */
    public static String load(String lang) {
        String code = (lang == null || lang.isBlank()) ? FALLBACK_LANGUAGE : lang.trim().toLowerCase();
        String text = read("about/about." + code + ".txt");
        if (text == null && !FALLBACK_LANGUAGE.equals(code)) {
            text = read("about/about." + FALLBACK_LANGUAGE + ".txt");
        }
        if (text == null) {
            throw new IllegalStateException("About resource not found: about/about." + code + ".txt");
        }
        return text;
    }

    private static String read(String path) {
        ClassLoader cl = AboutTexts.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read resource: " + path, ex);
        }
    }
}
