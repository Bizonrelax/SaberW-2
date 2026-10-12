package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.EntryValue;

/**
 * Strict left-to-right decompression parser (see Technical Specification section 4).
 * Never rescans already produced output. Recursion depth is limited to {@link #MAX_DEPTH}.
 */
public final class Decompressor {

    public static final int MAX_DEPTH = 1000;

    private final Dictionary dictionary;

    public Decompressor(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public String decompress(String compressed) {
        StringBuilder out = new StringBuilder(compressed.length());
        parse(compressed, out, 0);
        return out.toString();
    }

    private void parse(String s, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new DecompressionException("Recursion depth limit exceeded (" + MAX_DEPTH + ")");
        }
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            if (c == Shielder.ESCAPE) {
                if (i + 1 < n && s.charAt(i + 1) == Shielder.ESCAPE) {
                    // §§ -> literal section sign
                    out.append(Shielder.ESCAPE);
                    i += 2;
                    continue;
                }
                if (i + 1 < n && s.charAt(i + 1) == '#' && i + 2 < n) {
                    int cp = s.codePointAt(i + 2);
                    if (Shielder.isCjkExtensionA(cp)) {
                        // §#<CJK> -> literal CJK
                        out.appendCodePoint(cp);
                        i += 2 + Character.charCount(cp);
                        continue;
                    }
                }
                if (i + 2 < n) {
                    // §XY -> step-2 prefix code
                    int x = s.codePointAt(i + 1);
                    int yCp = s.codePointAt(i + 1 + Character.charCount(x));
                    if (Shielder.isCjkExtensionA(x) && Shielder.isCjkExtensionA(yCp)) {
                        String code = "§" + Character.toString((char) x) + Character.toString((char) yCp);
                        expandCode(code, out, depth, 3);
                        i += 3;
                        continue;
                    }
                }
                // Isolated section sign not forming any known atom: emit as-is
                out.append(c);
                i++;
                continue;
            }

            if (Shielder.isCjkExtensionA(c)) {
                // Isolated CJK Ext A -> step-1 code
                String code = String.valueOf(c);
                expandCode(code, out, depth, 1);
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
    }

    private void expandCode(String code, StringBuilder out, int depth, int consumedChars) {
        DictEntry entry = dictionary.get(code);
        if (entry == null) {
            throw new DecompressionException("Unknown code in dictionary: " + describe(code));
        }
        for (EntryValue.ValueToken t : entry.value().tokens()) {
            if ("text".equals(t.type())) {
                // Text tokens may contain escaping atoms; parse them too (without rescanning output)
                parse(t.text(), out, depth + 1);
            } else {
                parse(t.code(), out, depth + 1);
            }
        }
    }

    private static String describe(String code) {
        StringBuilder sb = new StringBuilder("U+");
        for (int cp : code.codePoints().toArray()) {
            sb.append(String.format("%04X ", cp));
        }
        return sb.toString().trim();
    }

    /** Raised on malformed compressed input or missing dictionary entries. */
    public static class DecompressionException extends RuntimeException {
        public DecompressionException(String message) {
            super(message);
        }
    }
}
