package com.saberw.core;

/**
 * Left-to-right escaping pass (see Docs/Техническое Задание, §2.1).
 * Guarantees unambiguous decompression:
 *  - section sign U+00A7            -> double section sign
 *  - CJK Ext A char (U+3400-U+4DBF) -> "ESCAPE # char"
 */
public final class Shielder {

    /** System escape prefix: U+00A7 SECTION SIGN. */
    public static final char ESCAPE = '§';

    private Shielder() {
    }

    public static boolean isCjkExtensionA(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x4DBF;
    }

    /** Escapes literal section signs and literal CJK Extension A characters. */
    public static String shield(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ESCAPE) {
                sb.append(ESCAPE).append(ESCAPE);
            } else if (isCjkExtensionA(cp)) {
                sb.append(ESCAPE).append('#').appendCodePoint(cp);
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /** Inverse of {@link #shield(String)}. */
    public static String unshield(String shielded) {
        if (shielded == null || shielded.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(shielded.length());
        for (int i = 0; i < shielded.length(); ) {
            char c = shielded.charAt(i);
            if (c == ESCAPE && i + 1 < shielded.length()) {
                char next = shielded.charAt(i + 1);
                if (next == ESCAPE) {
                    sb.append(ESCAPE);
                    i += 2;
                    continue;
                }
                if (next == '#' && i + 2 < shielded.length()) {
                    int cp = shielded.codePointAt(i + 2);
                    if (isCjkExtensionA(cp)) {
                        sb.appendCodePoint(cp);
                        i += 2 + Character.charCount(cp);
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
