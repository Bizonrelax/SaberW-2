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

    /** A compression candidate: a raw string plus its frequency, character length and token count. */
    private record Candidate(String raw, int frequency, int charLength, int tokenCount) {}

    public Result compress(String plainText) {
        return compress(plainText, new Dictionary(), CompressionProgress.NONE);
    }

    /**
     * Compresses using an existing (baseline) dictionary: entries already present reuse their
     * codes via the S_exist formula; genuinely new candidates get fresh step-1 codes.
     */
    public Result compress(String plainText, Dictionary baseline, CompressionProgress progress) {
        String prepared = settings.normalizeCase() ? plainText.toLowerCase(java.util.Locale.ROOT) : plainText;
        String shielded = Shielder.shield(prepared);
        report(progress, "shield", 5);

        List<SaberTokenizer.Token> tokens = new ArrayList<>(SaberTokenizer.tokenize(shielded));

        Map<String, Integer> wordFreq = countWords(tokens);
        report(progress, "words", 20);

        // Candidates: WORD tokens with L >= minWordLength and S >= threshold (step-1 code, C=1).
        // A candidate is stored only if it strictly improves compression (compressed < original),
        // i.e. F*L > F*C + C  =>  L > C * (F + 1) / F. With C = 1 this means L >= 3 for F = 2.
        Map<String, int[]> candidates = new LinkedHashMap<>(); // word -> [frequency, charLength]
        for (Map.Entry<String, Integer> e : wordFreq.entrySet()) {
            String word = e.getKey();
            int f = e.getValue();
            int l = word.length();
            if (l < settings.minWordLength() || f < settings.minFrequency()) {
                continue;
            }
            if (savingsNew(f, l, 1) >= settings.thresholdS() && savingsNew(f, l, 1) > 0) {
                candidates.put(word, new int[]{f, l});
            }
        }

        // Phrase pass (Technical Specification 3.2.3): suffix array + LCP over the token stream
        List<Candidate> phraseCandidates = findPhrases(tokens);
        report(progress, "phrases", 45);

        // Deterministic tie-break: by S desc, then L desc, then F desc, then lexicographic (Technical Specification 3.2.4)
        List<String> orderedWords = new ArrayList<>(candidates.keySet());
        orderedWords.sort(Comparator
                .comparingLong((String w) -> savingsNew(candidates.get(w)[0], candidates.get(w)[1], 1)).reversed()
                .thenComparingInt((String w) -> -candidates.get(w)[1])
                .thenComparingInt((String w) -> -candidates.get(w)[0])
                .thenComparing(Comparator.naturalOrder()));

        List<Candidate> orderedPhrases = new ArrayList<>(phraseCandidates);
        orderedPhrases.sort(Comparator
                .comparingLong((Candidate c) -> savingsNew(c.frequency(), c.charLength(), 1)).reversed()
                .thenComparingInt((Candidate c) -> -c.charLength())
                .thenComparingInt((Candidate c) -> -c.frequency())
                .thenComparing(Candidate::raw));

        // A phrase must not be a prefix of an already registered phrase: the longest-first
        // matcher would make the shorter one unreachable. Keep only maximal phrases.
        List<Candidate> maximalPhrases = new ArrayList<>();
        for (Candidate c : orderedPhrases) {
            boolean covered = false;
            for (Candidate longer : maximalPhrases) {
                if (longer.raw().length() > c.raw().length() && longer.raw().startsWith(c.raw())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                maximalPhrases.add(c);
            }
        }
        orderedPhrases = maximalPhrases;

        Dictionary dictionary = new Dictionary();
        Set<String> takenCodes = new HashSet<>();
        CodeAllocator allocator = new CodeAllocator();
        Map<String, String> wordToCode = new HashMap<>();
        Map<String, String> phraseToCode = new HashMap<>();

        // Seed with the baseline session dictionary: existing words/phrases keep their codes
        for (DictEntry be : baseline.entries()) {
            dictionary.put(be);
            takenCodes.add(be.code());
            String val = be.value().tokens().get(0).text();
            if (val.indexOf(' ') >= 0) {
                phraseToCode.put(val, be.code());
            } else {
                wordToCode.put(val, be.code());
            }
        }
        Set<String> knownValues = new HashSet<>();
        knownValues.addAll(wordToCode.keySet());
        knownValues.addAll(phraseToCode.keySet());

        for (String word : orderedWords) {
            // Do not re-register anything already present in the (baseline) dictionary
            if (knownValues.contains(word) || phraseToCode.containsKey(word)) {
                continue;
            }
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

        // A phrase is worth registering only if it covers at least one occurrence that no
        // registered word can cover (i.e. contains a token position not occupied by a coded
        // word). Otherwise words alone explain all its occurrences and the phrase is redundant.
        Set<String> codedWords = new HashSet<>(wordToCode.keySet());
        codedWords.addAll(baselineValues(tokens));
        for (Candidate c : orderedPhrases) {
            if (knownValues.contains(c.raw())) {
                continue; // already covered by the baseline dictionary
            }
            if (phraseFullyCoveredByWords(c.raw(), codedWords)) {
                continue; // words-only compression explains every occurrence of this phrase
            }
            String code = allocator.next(takenCodes);
            takenCodes.add(code);
            phraseToCode.put(c.raw(), code);
            dictionary.put(new DictEntry(
                    code,
                    new EntryValue(List.of(EntryValue.ValueToken.text(c.raw()))),
                    1,
                    c.frequency(),
                    0,
                    Instant.now().toString(),
                    false));
        }

        int maxPhraseTokens = settings.minPhraseLength();
        for (Candidate c : orderedPhrases) {
            maxPhraseTokens = Math.max(maxPhraseTokens, c.tokenCount());
        }
        for (String p : phraseToCode.keySet()) {
            maxPhraseTokens = Math.max(maxPhraseTokens, countWordTokens(p));
        }
        this.phraseMaxTokens = maxPhraseTokens;


        // Greedy replacement over the token list. Escape atoms are never touched (they are
        // single ESCAPE_ATOM tokens). At each position we pick the best-scoring option among
        // {longest matching phrase, matching word}: the one with maximal net savings
        // F*(L-C) - C >= 0, longest wins on ties. This guarantees the compressed output is
        // never longer than the input and that no registered entry ever goes unused.
        StringBuilder out = new StringBuilder(shielded.length());
        Map<String, Integer> appliedCount = new HashMap<>();
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            if (progress != null && progress.isCancelled()) {
                throw new CompressionInterruptedException(partialResult(out, tokens, i, dictionary));
            }
            int[] decision = chooseReplacement(tokens, i, wordToCode, phraseToCode);
            if (decision != null) {
                int len = decision[0];
                String code = len == 1 ? wordToCode.get(tokens.get(i).text())
                                       : phraseToCode.get(joinRaw(tokens, i, len));
                out.append(code);
                String raw = len == 1 ? tokens.get(i).text() : joinRaw(tokens, i, len);
                appliedCount.merge(raw, 1, Integer::sum);
                i += len;
            } else {
                out.append(tokens.get(i).text());
                i++;
            }
            if ((i & 0x3FF) == 0) {
                report(progress, "replace", 45 + 55 * i / Math.max(1, n));
            }
        }
        report(progress, "replace", 100);

        for (DictEntry entry : dictionary.entries()) {
            String value = entry.value().tokens().get(0).text();
            int applied = appliedCount.getOrDefault(value, 0);
            if (applied > 0) {
                dictionary.put(entry.withApplied(applied));
            }
        }
        // Merge frequencies for reused baseline entries (S_exist bookkeeping, Technical Specification 3.1)
        for (Map.Entry<String, Integer> e : appliedCount.entrySet()) {
            DictEntry cur = null;
            String code = wordToCode.get(e.getKey());
            if (code == null) {
                code = phraseToCode.get(e.getKey());
            }
            if (code != null) {
                cur = dictionary.get(code);
            }
            if (cur != null && cur.frequency() < e.getValue()) {
                dictionary.put(new DictEntry(cur.code(), cur.value(), cur.level(), e.getValue(),
                        cur.codeAppliedCount(), cur.addedAt(), cur.sentToBot()));
            }
        }

        return new Result(out.toString(), dictionary);
    }

    /** Thrown when the user stops the background compression at an iteration boundary. */
    public static class CompressionInterruptedException extends RuntimeException {
        private final Result partial;

        public CompressionInterruptedException(Result partial) {
            super("Compression interrupted");
            this.partial = partial;
        }

        public Result partialResult() {
            return partial;
        }
    }

    private static void report(CompressionProgress progress, String phase, int percent) {
        if (progress != null) {
            progress.update(phase, percent);
        }
    }

    /** Builds a recoverable result from the prefix processed so far (used on interruption). */
    private Result partialResult(StringBuilder out, List<SaberTokenizer.Token> tokens, int index, Dictionary dict) {
        StringBuilder full = new StringBuilder(out);
        for (int k = index; k < tokens.size(); k++) {
            full.append(tokens.get(k).text());
        }
        return new Result(full.toString(), dict);
    }

    /**
     * Finds repeated phrases (runs of >= minPhraseLength consecutive WORD tokens) using a
     * suffix-array + LCP approach over the token-id stream (Technical Specification 3.2.3).
     * Returns candidates that satisfy the savings formula for step-1 codes.
     */
    List<Candidate> findPhrases(List<SaberTokenizer.Token> tokens) {
        if (settings.minPhraseLength() < 2 || phraseMaxTokens == 0) {
            return List.of(); // phrase pass disabled by settings
        }
        // Map each WORD token to an id; non-word tokens break phrase runs.
        Map<String, Integer> ids = new HashMap<>();
        int[] stream = new int[tokens.size()];
        java.util.Arrays.fill(stream, -1);
        for (int i = 0; i < tokens.size(); i++) {
            SaberTokenizer.Token t = tokens.get(i);
            if (t.type() == SaberTokenizer.TokenType.WORD) {
                stream[i] = ids.computeIfAbsent(t.text(), w -> ids.size());
            }
        }

        // Collect all WORD positions into a compact sequence with run-boundary awareness:
        // a phrase is a contiguous slice of the token list consisting only of WORD tokens.
        // We build the list of "word-only segments" and run suffix analysis inside each segment.
        Map<String, int[]> found = new LinkedHashMap<>(); // phraseRaw -> [frequency, charLength]
        int segStart = -1;
        for (int i = 0; i <= stream.length; i++) {
            boolean isWord = i < stream.length && stream[i] >= 0;
            if (isWord && segStart < 0) {
                segStart = i;
            }
            if (!isWord && segStart >= 0) {
                int segEnd = i; // exclusive
                extractPhrasesFromSegment(tokens, stream, segStart, segEnd, ids, found);
                segStart = -1;
            }
        }

        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : found.entrySet()) {
            int f = e.getValue()[0];
            int l = e.getValue()[1];
            int tokensCnt = e.getValue()[2];
            if (f >= settings.minFrequency() && savingsNew(f, l, 1) > 0) {
                result.add(new Candidate(e.getKey(), f, l, tokensCnt));
            }
        }
        return result;
    }

    /** Suffix-array + LCP phrase extraction within one segment of consecutive WORD tokens. */
    private void extractPhrasesFromSegment(List<SaberTokenizer.Token> tokens, int[] stream,
                                           int segStart, int segEnd,
                                           Map<String, Integer> ids,
                                           Map<String, int[]> found) {
        int len = segEnd - segStart;
        if (len < settings.minPhraseLength()) {
            return;
        }
        // Local id sequence for this segment
        int[] seq = new int[len];
        for (int k = 0; k < len; k++) {
            seq[k] = stream[segStart + k];
        }
        // Simple O(n log n) suffix array via sorting indices with comparator on suffixes
        Integer[] sa = new Integer[len];
        for (int k = 0; k < len; k++) {
            sa[k] = k;
        }
        java.util.Arrays.sort(sa, (a, b) -> compareSuffixes(seq, a, b));

        // LCP between adjacent suffixes in SA order
        int[] lcp = new int[len];
        for (int k = 1; k < len; k++) {
            lcp[k] = commonPrefixLength(seq, sa[k - 1], sa[k]);
        }

        // For every minimum phrase length, group suffixes sharing an LCP >= p
        for (int p = settings.minPhraseLength(); p <= len; p++) {
            int k = 1;
            while (k < len) {
                if (lcp[k] < p) {
                    k++;
                    continue;
                }
                int groupStart = k - 1;
                int j = k;
                while (j < len && lcp[j] >= p) {
                    j++;
                }
                int freq = j - groupStart; // number of suffixes in the group
                // The shared prefix of the group has length >= p; take exactly p tokens
                int base = sa[groupStart];
                String raw = joinRaw(tokens, segStart + base, p);
                int charLen = raw.length();
                if (charLen >= settings.minWordLength()) {
                    int[] prev = found.get(raw);
                    if (prev == null || freq > prev[0]) {
                        // [frequency, charLength, tokenCount]
                        found.put(raw, new int[]{freq, charLen, p});
                    }
                }
                k = j;
            }
        }
    }

    private static int compareSuffixes(int[] seq, int a, int b) {
        int n = seq.length;
        int i = a, j = b;
        while (i < n && j < n) {
            if (seq[i] != seq[j]) {
                return Integer.compare(seq[i], seq[j]);
            }
            i++;
            j++;
        }
        return Integer.compare(n - a, n - b);
    }

    private static int commonPrefixLength(int[] seq, int a, int b) {
        int n = seq.length;
        int c = 0;
        while (a + c < n && b + c < n && seq[a + c] == seq[b + c]) {
            c++;
        }
        return c;
    }

    /** Baseline dictionary values that consist of single words (already coded). */
    private Set<String> baselineValues(List<SaberTokenizer.Token> tokens) {
        return Set.of(); // baseline words are already inside wordToCode via seeding
    }

    /** True if every WORD token of the raw phrase (space-joined) is a registered/coded word. */
    private static boolean phraseFullyCoveredByWords(String rawPhrase, Set<String> codedWords) {
        for (String part : rawPhrase.split(" ")) {
            if (!part.isEmpty() && !codedWords.contains(part)) {
                return false;
            }
        }
        return true;
    }

    /** Number of WORD tokens a space-joined phrase raw string consists of. */
    private static int countWordTokens(String rawPhrase) {
        int cnt = 1;
        for (int i = 0; i < rawPhrase.length(); i++) {
            if (rawPhrase.charAt(i) == ' ') {
                cnt++;
            }
        }
        return cnt;
    }

    private static String joinRaw(List<SaberTokenizer.Token> tokens, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < start + count; k++) {
            sb.append(tokens.get(k).text());
        }
        return sb.toString();
    }

    /**
     * Chooses the best replacement at position {@code i}: among all matching phrases
     * (longest-first scan) and the matching single word, picks the option with maximal
     * net saving L - 2 (C = 1: replacing L chars by 1 code char saves L-1 minus 1 char of
     * amortized dictionary overhead). Returns {tokenLength} or null when nothing pays off.
     */
    private int[] chooseReplacement(List<SaberTokenizer.Token> tokens, int i,
                                    Map<String, String> wordToCode,
                                    Map<String, String> phraseToCode) {
        int n = tokens.size();
        int bestLen = 0;
        long bestGain = 0;
        // Word candidate
        SaberTokenizer.Token t = tokens.get(i);
        if (t.type() == SaberTokenizer.TokenType.WORD && wordToCode.containsKey(t.text())) {
            long gain = t.text().length() - 2L;
            if (gain > bestGain) {
                bestGain = gain;
                bestLen = 1;
            }
        }
        // Phrase candidates: longest-first, first positive gain wins (longer => bigger gain)
        if (phraseMaxTokens > 0) {
            for (int p = Math.min(phraseMaxTokens, n - i); p >= settings.minPhraseLength(); p--) {
                if (i + p > n) {
                    continue;
                }
                String raw = joinRaw(tokens, i, p);
                if (phraseToCode.containsKey(raw)) {
                    long gain = raw.length() - 2L;
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestLen = p;
                    }
                    break; // longest match considered; shorter ones cannot beat it in length
                }
            }
        }
        return bestLen == 0 ? null : new int[]{bestLen};
    }

    private int phraseMaxTokens = 0;

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
