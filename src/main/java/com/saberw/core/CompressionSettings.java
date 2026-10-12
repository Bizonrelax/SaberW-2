package com.saberw.core;

/** Compression parameters (see Technical Specification sections 7 and 9.2). Immutable settings holder for the core layer. */
public record CompressionSettings(
        int minWordLength,
        int minFrequency,
        int minPhraseLength,
        int thresholdS,
        boolean normalizeCase) {

    /** Preset "max-cjk" (default preset, see Technical Specification section 7). */
    public static CompressionSettings maxCjk() {
        return new CompressionSettings(3, 2, 4, 0, true);
    }

    /** Preset "code". */
    public static CompressionSettings code() {
        return new CompressionSettings(4, 2, 4, 0, false);
    }

    /** Preset "chat". */
    public static CompressionSettings chat() {
        return new CompressionSettings(3, 2, 3, 0, true);
    }
}
