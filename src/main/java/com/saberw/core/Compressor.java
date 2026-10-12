package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.EntryValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SaberW-2 MVP compression core (Level 1 only, see Technical Specification section 3).
 * Contains no UI strings; fully separated from the GUI layer.
 */
public final class Compressor {

    public record Result(String compressedText, Dictionary dictionary) {}

    private final CompressionSettings settings;

    public Compressor(CompressionSettings settings) {
        this.settings = settings;
    }

    /** Savings formula for a new candidate: S_new = (F - 1) * L - F * C - C  (Technical Specification 3.1). */
    public static long savingsNew(int frequency, int lengthChars, int codeLen) {
        return (long) (frequency - 1) * lengthChars - (long) frequency * codeLen - codeLen;
    }

    /** Savings formula for an existing entry: S_exist = F * (L - C)  (Technical Specification 3.1). */
    public static long savingsExist(int frequency, int lengthChars, int codeLen) {
        return (long) frequency * (lengthChars - codeLen);
    }

    public Result compress(String plainText) {
        String prepared = settings.normalizeCase() ? plainText.toLowerCase(java.util.Locale.ROOT) : plainText;
        String shielded = Shielder.shield(prepared);

        List<SaberTokenizer.Token> tokens = new ArrayList<>(SaberTokenizer.tokenize(shielded));

        Map<String, Integer> wordFreq = countWords(tokens);

        // Candidates: WORD tokens with L >= minWordLength and S >= threshold (step-1 code, C=1)
        Map<String, int[]> candidates = new LinkedHashMap<>(); // word -> [frequency, charLength]
        for (Map.Entry<String, Integer> e : wordFreq.entrySet()) {
            String word = e.getKey();
            int f = e.getValue();
            int l = word.length();
            if (l < settings.minWordLength() || f < settings.minFrequency()) {
                continue;
            }
            if (savingsNew(f, l, 1) >= settings.thresholdS()) {
                candidates.put(word, new int[]{f, l});
            }
        }

        // Deterministic tie-break: by S desc, then L desc, then F desc, then lexicographic (Technical Specification 3.2.4)
        List<String> ordered = new ArrayList<>(candidates.keySet());
        ordered.sort(Comparator
                .comparingLong((String w) -> savingsNew(candidates.get(w)[0], candidates.get(w)[1], 1)).reversed()
                .thenComparingInt((String w) -> -candidates.get(w)[1])
                .thenComparingInt((String w) -> -candidates.get(w)[0])
                .thenComparing(Comparator.naturalOrder()));

        Dictionary dictionary = new Dictionary();
        Set<String> takenCodes = new HashSet<>();
        CodeAllocator allocator = new CodeAllocator();
        Map<String, String> wordToCode = new HashMap<>();

        for (String word : ordered) {
            String code = allocator.next(takenCodes);
            takenCodes.add(code);
            wordToCode.put(word, code);
            dictionary.put(new DictEntry(
                    code,
                    new EntryValue(List.of(EntryValue.ValueToken.text(word))),
                    1,
                    candidates.get(word)[0],
                    0,
                    Instant.now().toString(),
                    false));
        }

        // Greedy replacement over the token list (escape atoms are never touched — they are
        // single ESCAPE_ATOM tokens, codes replace only whole WORD tokens)
        StringBuilder out = new StringBuilder(shielded.length());
        Map<String, Integer> appliedCount = new HashMap<>();
        for (SaberTokenizer.Token t : tokens) {
            if (t.type() == SaberTokenizer.TokenType.WORD) {
                String code = wordToCode.get(t.text());
                if (code != null) {
                    out.append(code);
                    appliedCount.merge(t.text(), 1, Integer::sum);
                    continue;
                }
            }
            out.append(t.text());
        }

        for (DictEntry entry : dictionary.entries()) {
            String word = entry.value().tokens().get(0).text();
            int applied = appliedCount.getOrDefault(word, 0);
            if (applied > 0) {
                dictionary.put(entry.withApplied(applied));
            }
        }

        return new Result(out.toString(), dictionary);
    }

    private Map<String, Integer> countWords(List<SaberTokenizer.Token> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (SaberTokenizer.Token t : tokens) {
            if (t.type() == SaberTokenizer.TokenType.WORD) {
                freq.merge(t.text(), 1, Integer::sum);
            }
        }
        return freq;
    }
}
