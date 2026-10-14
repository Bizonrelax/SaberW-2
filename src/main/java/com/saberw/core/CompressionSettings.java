package com.saberw.core;

/** Compression parameters (see Technical Specification sections 7 and 9.2). Immutable settings holder for the core layer. */
public record CompressionSettings(
        int minWordLength,
        int minFrequency,
        int minPhraseLength,
        int thresholdS,
        boolean normalizeCase,
        int minFrequencyForStep2,
        boolean ignoreComments) {

    /** Preset "max-cjk" (default preset, see Technical Specification section 7). */
    public static CompressionSettings maxCjk() {
        return new CompressionSettings(3, 2, 7, 0, true, 6, false);
    }

    /** Preset "code". */
    public static CompressionSettings code() {
        return new CompressionSettings(4, 2, 7, 0, false, 6, false);
    }

    /** Preset "chat". */
    public static CompressionSettings chat() {
        return new CompressionSettings(3, 2, 5, 0, true, 6, false);
    }

    /** Backwards-compatible constructor: step-2 frequency gate defaults to 6, comments are counted. */
    public CompressionSettings(int minWordLength, int minFrequency, int minPhraseLength,
                               int thresholdS, boolean normalizeCase) {
        this(minWordLength, minFrequency, minPhraseLength, thresholdS, normalizeCase, 6, false);
    }
}
