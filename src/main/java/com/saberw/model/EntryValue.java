package com.saberw.model;

import java.util.List;

/** Dictionary entry value: an ordered list of tokens (plain text pieces and code references). */
public record EntryValue(List<ValueToken> tokens) {

    public record ValueToken(String type, String text, String code) {
        public static ValueToken text(String escapedText) {
            return new ValueToken("text", escapedText, null);
        }

        public static ValueToken codeRef(String code) {
            return new ValueToken("code", null, code);
        }
    }

    /** Total length in characters of the stored representation. */
    public int charLength() {
        int sum = 0;
        for (ValueToken t : tokens) {
            if ("text".equals(t.type())) {
                sum += t.text().length();
            } else {
                sum += t.code().length();
            }
        }
        return sum;
    }
}
