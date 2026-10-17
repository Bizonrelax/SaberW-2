package com.saberw.core;

/** Compression parameters (see Technical Specification sections 7 and 9.2). Immutable settings holder for the core layer. */
public record CompressionSettings(
        int minWordLength,
        int minFrequency,
        int minPhraseLength,
        int thresholdS,
        boolean normalizeCase,
        boolean ignoreComments) {

    /** Secondary constructor without the ignoreComments flag; defaults it to false (TZ 3.2.1, 12.2). */
    public CompressionSettings(
            int minWordLength,
            int minFrequency,
            int minPhraseLength,
            int thresholdS,
            boolean normalizeCase) {
        this(minWordLength, minFrequency, minPhraseLength, thresholdS, normalizeCase, false);
    }

    /** Returns a copy of these settings with the given ignoreComments value. */
    public CompressionSettings withIgnoreComments(boolean value) {
        return new CompressionSettings(minWordLength, minFrequency, minPhraseLength,
                thresholdS, normalizeCase, value);
    }

    /** Preset "max-cjk" (default preset, see Technical Specification section 7). */
    public static CompressionSettings maxCjk() {
        return new CompressionSettings(3, 2, 7, 0, true, false);
    }

    /** Preset "code". */
    public static CompressionSettings code() {
        return new CompressionSettings(4, 2, 7, 0, false, false);
    }

    /** Preset "chat". */
    public static CompressionSettings chat() {
        return new CompressionSettings(3, 2, 5, 0, true, false);
    }
}
