package com.saberw.core;

/**
 * Progress / cancellation callbacks for the compression pipeline
 * (see Technical Specification section 6: no EDT blocking, instant stop on Esc).
 * The core layer stays free of any UI strings — this is a pure listener interface.
 */
public interface CompressionProgress {

    /** Called periodically from the background thread. Return true to abort at the next iteration boundary. */
    boolean isCancelled();

    /**
     * @param phase       machine-readable phase name ("shield", "words", "phrases", "replace")
     * @param percentDone 0..100, -1 if indeterminate
     */
    void update(String phase, int percentDone);

    /** No-op progress: never cancels, reports nothing. */
    CompressionProgress NONE = new CompressionProgress() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void update(String phase, int percentDone) {
            // no-op
        }
    };
}
