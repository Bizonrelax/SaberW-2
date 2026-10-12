package com.saberw.core;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/*
 * Reference tokenizer implementation provided in the project Technical Specification
 * (Docs/Техническое Задание/Основное/ТЗ.md, "ДОПОЛНЕНИЕ №2: Эталонная реализация
 * Токенизатора"). Authored as part of SaberW-2 by the project owner (Bizonrelax),
 * supplied to the implementing AI as a hard architectural guideline.
 * Adapted here only minimally so that atomic escape units are also recognized when
 * built from multi-code-point grapheme clusters (robustness fix, same semantics).
 */
public class SaberTokenizer {

    public enum TokenType {
        ESCAPE_ATOM, // §§ or §#<CJK>
        WORD,        // Letters, digits, underscore
        EMOJI,       // Grapheme clusters (emoji, flags, modifiers)
        SEPARATOR    // All other characters
    }

    public static record Token(TokenType type, String text) {}

    public static List<Token> tokenize(String shieldedText) {
        List<Token> tokens = new ArrayList<>();
        if (shieldedText == null || shieldedText.isEmpty()) {
            return tokens;
        }

        // Step 1: split the text into true grapheme clusters (handles complex emoji)
        List<String> clusters = new ArrayList<>();
        BreakIterator boundary = BreakIterator.getCharacterInstance();
        boundary.setText(shieldedText);
        int start = boundary.first();
        for (int end = boundary.next(); end != BreakIterator.DONE; start = end, end = boundary.next()) {
            clusters.add(shieldedText.substring(start, end));
        }

        // Step 2: greedy left-to-right token collector with priorities
        int i = 0;
        int n = clusters.size();

        while (i < n) {
            String current = clusters.get(i);

            // 1. Atomic escaping units have the highest priority
            if (current.equals("§")) {
                if (i + 1 < n && clusters.get(i + 1).equals("§")) {
                    tokens.add(new Token(TokenType.ESCAPE_ATOM, "§§"));
                    i += 2;
                    continue;
                }
                if (i + 1 < n && clusters.get(i + 1).equals("#") && i + 2 < n) {
                    String cjkCandidate = clusters.get(i + 2);
                    if (isSingleCjkExtensionA(cjkCandidate)) {
                        tokens.add(new Token(TokenType.ESCAPE_ATOM, "§#" + cjkCandidate));
                        i += 3;
                        continue;
                    }
                }
            }

            // 2. Word class (letters, digits, '_', excluding CJK Ext A)
            int firstCodePoint = current.codePointAt(0);
            if (isWordPart(firstCodePoint) && !Shielder.isCjkExtensionA(firstCodePoint)) {
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    String wordChar = clusters.get(i);
                    int cp = wordChar.codePointAt(0);
                    // Interrupt the word if we hit an escaping prefix
                    if (wordChar.equals("§")) break;
                    if (isWordPart(cp) && !Shielder.isCjkExtensionA(cp)) {
                        sb.append(wordChar);
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new Token(TokenType.WORD, sb.toString()));
                continue;
            }

            // 3. Emoji class (grapheme cluster that is neither a word nor a separator)
            if (isEmojiCluster(current)) {
                tokens.add(new Token(TokenType.EMOJI, current));
                i++;
                continue;
            }

            // 4. Separator class (collect the maximal run)
            StringBuilder sepSb = new StringBuilder();
            while (i < n) {
                String sepChar = clusters.get(i);
                int cp = sepChar.codePointAt(0);

                // Separator interruption triggers: escape start, word, or emoji
                if (sepChar.equals("§")) break;
                if ((isWordPart(cp) && !Shielder.isCjkExtensionA(cp)) || isEmojiCluster(sepChar)) break;

                sepSb.append(sepChar);
                i++;
            }
            tokens.add(new Token(TokenType.SEPARATOR, sepSb.toString()));
        }

        return tokens;
    }

    /** True if the cluster is exactly one CJK Extension A code point. */
    private static boolean isSingleCjkExtensionA(String cluster) {
        if (cluster.length() != 1) {
            return false;
        }
        return Shielder.isCjkExtensionA(cluster.charAt(0));
    }

    private static boolean isWordPart(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isEmojiCluster(String cluster) {
        // Basic detection: Unicode emoji blocks or surrogate pairs that are not punctuation
        int cp = cluster.codePointAt(0);
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cluster.length() > 1 && Character.isSurrogate(cluster.charAt(0)));
    }
}
