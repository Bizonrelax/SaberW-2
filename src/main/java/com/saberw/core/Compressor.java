package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.EntryValue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
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

    /** A compression candidate: a raw string plus its frequency, character length and token count. Mutable: F/S are recomputed each greedy iteration. */
    static final class Candidate {
        final String raw;
        final int charLength;
        final int tokenCount;
        /** Atom span of an occurrence in the token stream (words + separators between them). */
        int spanTokens;
        int frequency;
        long savings;

        Candidate(String raw, int frequency, int charLength, int tokenCount) {
            this.raw = raw;
            this.frequency = frequency;
            this.charLength = charLength;
            this.tokenCount = tokenCount;
            this.spanTokens = countWordTokens(raw); // conservative default, refined in compress()
        }

        int spanTokens() { return spanTokens; }

        String raw() { return raw; }
        int frequency() { return frequency; }
        void frequency(int f) { this.frequency = f; }
        int charLength() { return charLength; }
        int tokenCount() { return tokenCount; }
        long savings() { return savings; }
        void savings(long s) { this.savings = s; }
    }

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

        // Comment masking (Technical Specification 3.2.1, 12.4): tokens inside comment ranges
        // are excluded from word/phrase frequencies, code simulation and replacement, so that
        // registered codes never overlap a comment and round-trip stays lossless.
        List<int[]> commentRanges = findCommentRanges(tokens);
        boolean[] mask = buildMask(commentRanges, tokens.size());
        this.maskedToken = commentRanges.isEmpty() ? null : mask;

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

        // Unified candidate pool (Technical Specification 3.2.4): single WORD entries and
        // multi-token phrases compete in ONE greedy loop. The loop is LAZY: frequencies are
        // computed once up front and only the winner's neighbourhood (candidates sharing any
        // word with it) is re-scored after each registration - recomputing the WHOLE pool on
        // every iteration was O(iterations x pool x text) and exhausted the JVM heap/time
        // budget on realistic inputs. The best-savings candidate wins and gets the next
        // step-1 code. Candidates that drop below minFrequency/thresholdS are pruned.
        Comparator<Candidate> bySavings = Comparator
                .comparingLong((Candidate c) -> c.savings()).reversed()
                .thenComparingInt((Candidate c) -> -c.charLength())
                .thenComparingInt((Candidate c) -> -c.frequency())
                .thenComparingInt((Candidate c) -> -c.tokenCount())
                .thenComparing(Candidate::raw);

        Map<String, Candidate> remaining = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : candidates.entrySet()) {
            int[] fr = e.getValue();
            remaining.put(e.getKey(), new Candidate(e.getKey(), fr[0], fr[1], 1));
        }
        // Maximal-phrase filter at the TOKEN level: a phrase is dropped only if some longer
        // candidate matches it token-by-token as a contiguous slice. A mere string prefix is
        // NOT enough: separators between tokens (e.g. ". ") make the raw strings look like
        // prefixes while the extra token never actually matches. On top of that, a phrase whose
        // exact word sequence never occurs in the token stream (e.g. one glued across a NEWLINE
        // by the space-joined raw representation) is NOT applicable to the replacement pass:
        // registering such a phrase creates a dead code that drags all of its working
        // sub-phrases down during the pruning cascade (root cause of repeatedPhraseGetsCoded).
        List<Candidate> sortedByTokensDesc = new ArrayList<>(phraseCandidates);
        sortedByTokensDesc.sort(Comparator.comparingInt(Candidate::tokenCount).reversed());
        List<Candidate> maximalPhrases = new ArrayList<>();
        for (Candidate c : sortedByTokensDesc) {
            String[] parts = c.raw().split(" ");
            boolean covered = false;
            for (Candidate longer : maximalPhrases) {
                if (longer.tokenCount() <= c.tokenCount()) {
                    break; // sorted desc: nothing further can strictly contain c's length
                }
                if (containsTokenSequence(longer.raw().split(" "), parts)) {
                    covered = true;
                    break;
                }
            }
            if (!covered && isApplicablePhrase(tokens, parts)) {
                maximalPhrases.add(c);
            }
        }
        for (Candidate c : maximalPhrases) {
            remaining.putIfAbsent(c.raw(), c);
        }

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

        // Phrase window bound must be set BEFORE the registration loop: simulateCodes()
        // uses it to scan for already-registered entries. The window is measured in ATOMS
        // (not words): a phrase spanning k words plus separator atoms between them occupies
        // more atoms than tokenCount(), so the bound must account for the widest actual token
        // span of any pool candidate - otherwise simulateCodes/chooseReplacement can never
        // see the full phrase and its frequency collapses to 0 (dead-code cascade).
        int maxPhraseTokens = settings.minPhraseLength();
        for (Candidate c : phraseCandidates) {
            c.spanTokens = Math.max(c.tokenCount(), maxSpanAtoms(tokens, c.raw()));
            maxPhraseTokens = Math.max(maxPhraseTokens, c.spanTokens);
        }
        for (Map.Entry<String, Candidate> e : remaining.entrySet()) {
            Candidate c = e.getValue();
            if (c.spanTokens <= 1) { // word candidates: refine against the real stream too
                c.spanTokens = Math.max(1, maxSpanAtoms(tokens, c.raw()));
            }
        }
        for (String p : phraseToCode.keySet()) {
            maxPhraseTokens = Math.max(maxPhraseTokens, countWordTokens(p));
        }
        this.phraseMaxTokens = maxPhraseTokens;

        // ---- Greedy registration loop (Technical Specification 3.2.4, lazy core) ----
        // The simulated stream is rebuilt ONLY when a WORD wins (a word code can collapse
        // occurrences of every phrase containing it). A phrase win never changes the match
        // result of any other candidate: at each position the longest registered entry wins,
        // and a maximal pool phrase that token-matches another pool phrase was already
        // removed by the maximal-phrase filter above - so only the winner itself leaves the
        // pool. This makes the loop O(registrations x text) in the worst case instead of
        // O(registrations x pool x text), which previously caused Java heap exhaustion.
        List<Candidate> appliedOrder = new ArrayList<>();
        SimToken[] sim = simulateCodes(tokens, wordToCode, phraseToCode,
                Math.max(this.phraseMaxTokens, maxTokenCount(remaining)));
        for (Candidate c : remaining.values()) {
            c.frequency(countRawOccurrences(sim, c.raw()));
            c.savings(totalSavings(c));
        }
        while (!remaining.isEmpty()) {
            if (progress != null && progress.isCancelled()) {
                break; // keep the dictionary built so far; the replace pass will abort below
            }
            // Prune hopeless candidates, then pick the deterministic winner.
            List<Candidate> pool = new ArrayList<>();
            List<String> doomed = new ArrayList<>();
            for (Map.Entry<String, Candidate> e : remaining.entrySet()) {
                Candidate c = e.getValue();
                if (c.frequency() < settings.minFrequency() || c.savings() <= settings.thresholdS()
                        || c.savings() <= 0) {
                    doomed.add(e.getKey());
                } else {
                    pool.add(c);
                }
            }
            for (String key : doomed) {
                remaining.remove(key);
            }
            if (pool.isEmpty()) {
                break;
            }
            pool.sort(bySavings);
            Candidate winner = pool.get(0);
            String code = allocator.next(takenCodes);
            takenCodes.add(code);
            boolean wordWin = winner.tokenCount() == 1;
            if (wordWin) {
                wordToCode.put(winner.raw(), code);
            } else {
                phraseToCode.put(winner.raw(), code);
            }
            dictionary.put(new DictEntry(
                    code,
                    new EntryValue(List.of(EntryValue.ValueToken.text(winner.raw()))),
                    1,
                    winner.frequency(),
                    0,
                    Instant.now().toString(),
                    false));
            appliedOrder.add(winner);
            remaining.remove(winner.raw());
            if (wordWin) {
                // Words changed -> every frequency must be re-measured against the new stream.
                sim = simulateCodes(tokens, wordToCode, phraseToCode,
                        Math.max(this.phraseMaxTokens, maxTokenCount(remaining)));
                for (Candidate c : remaining.values()) {
                    c.frequency(countRawOccurrences(sim, c.raw()));
                    c.savings(totalSavings(c));
                }
            }
        }
        report(progress, "register", 60);

        // Baseline values that never occur in this text stay in the dictionary (session state);
        // only genuinely NEW dead registrations are pruned below.
        Set<String> baselineOnly = new HashSet<>(knownValues);

        // Greedy replacement over the token list. Escape atoms are never touched (they are
        // single ESCAPE_ATOM tokens). At each position we pick the best-scoring option among
        // {longest matching phrase, matching word}: the one with maximal net savings
        // L - 2 (C = 1), longest wins on ties. This guarantees the compressed output is
        // never longer than the input and that no registered entry ever goes unused.
        StringBuilder out = new StringBuilder(shielded.length());
        Map<String, Integer> appliedCount = new HashMap<>();
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            if (progress != null && progress.isCancelled()) {
                throw new CompressionInterruptedException(partialResult(out, tokens, i, dictionary));
            }
            int[] decision = (maskedToken != null && maskedToken[i])
                    ? null : chooseReplacement(tokens, i, wordToCode, phraseToCode, false);
            if (decision != null) {
                int len = decision[0];
                String raw = len == 1 ? tokens.get(i).text() : joinedWordRaw(tokens, i, len);
                String code = len == 1 ? wordToCode.get(raw) : phraseToCode.get(raw);
                out.append(code);
                appliedCount.merge(raw, 1, Integer::sum);
                i += len;
            } else {
                out.append(tokens.get(i).text());
                i++;
            }
            if ((i & 0x3FF) == 0) {
                report(progress, "replace", 60 + 35 * i / Math.max(1, n));
            }
        }
        report(progress, "replace", 95);

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

        // Prune pass: a freshly registered entry that was never actually applied in the
        // final left-to-right pass is a "dead code" - remove it from the dictionary.
        // Baseline (session) entries are NEVER removed even if unused in this text.
        // The cascade runs over the registration order in ONE pass with a per-value memo:
        // testing value v against the registrations made BEFORE v reproduces exactly the
        // frequency v had when it won (later winners cannot affect earlier simulations),
        // so no repeated whole-text re-simulation is needed - the old loop cost
        // O(registrations x pool x text) and exhausted the JVM heap on larger inputs.
        Map<String, Boolean> appliedMemo = new HashMap<>();
        for (int idx = 0; idx < appliedOrder.size(); idx++) {
            Candidate w = appliedOrder.get(idx);
            if (appliedCount.containsKey(w.raw()) || baselineOnly.contains(w.raw())) {
                continue; // genuinely used (or session state): keep unconditionally
            }
            if (wasEverApplied(appliedOrder, idx, tokens, appliedMemo)) {
                continue; // applied inside an earlier winner's collapse: still a live code
            }
            String code = wordToCode.remove(w.raw());
            if (code == null) {
                code = phraseToCode.remove(w.raw());
            }
            if (code != null) {
                dictionary.remove(code);
            }
        }

        // Final bookkeeping: frequencies recorded during greedy registration may be stale
        // after the prune pass; recompute them once against the surviving registrations.
        if (!appliedOrder.isEmpty()) {
            sim = simulateCodes(tokens, wordToCode, phraseToCode, this.phraseMaxTokens);
            for (DictEntry en : dictionary.entries()) {
                if (baselineOnly.contains(en.value().tokens().get(0).text())) {
                    continue; // baseline entries keep their accumulated session frequency
                }
                int f = countRawOccurrences(sim, en.value().tokens().get(0).text());
                if (f != en.frequency()) {
                    dictionary.put(new DictEntry(en.code(), en.value(), en.level(), f,
                            en.codeAppliedCount(), en.addedAt(), en.sentToBot()));
                }
            }
        }

        return new Result(out.toString(), dictionary);
    }

    /**
     * True when the candidate at {@code index} occurred at least twice in the stream
     * simulated with ONLY the registrations made before it (the exact conditions under
     * which it won its greedy iteration). Results are memoised per value because a later
     * candidate shares most of its prefix with earlier ones.
     */
    private boolean wasEverApplied(List<Candidate> order, int index,
                                   List<SaberTokenizer.Token> tokens,
                                   Map<String, Boolean> memo) {
        Candidate c = order.get(index);
        Boolean cached = memo.get(c.raw());
        if (cached != null) {
            return cached;
        }
        Map<String, String> w = new HashMap<>();
        Map<String, String> p = new HashMap<>();
        int window = Math.max(Math.max(this.phraseMaxTokens, c.tokenCount()), c.spanTokens);
        for (int k = 0; k < index; k++) {
            Candidate e = order.get(k);
            if (Boolean.FALSE.equals(memo.get(e.raw()))) {
                continue; // known dead: it was removed from the maps by the prune pass
            }
            if (e.tokenCount() == 1) {
                w.put(e.raw(), "");
            } else {
                p.put(e.raw(), "");
            }
            memo.putIfAbsent(e.raw(), true);
        }
        SimToken[] sim = simulateCodes(tokens, w, p, window);
        boolean res = countRawOccurrences(sim, c.raw()) >= settings.minFrequency();
        memo.put(c.raw(), res);
        return res;
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
        if (settings.minPhraseLength() < 2) {
            return List.of(); // phrase pass disabled by settings
        }
        // Collect all WORD positions into a compact sequence with run-boundary awareness:
        // a phrase is a contiguous slice of the token list consisting only of WORD tokens.
        // We build the list of "word-only segments" and run suffix analysis inside each segment.
        Map<String, int[]> found = new LinkedHashMap<>(); // phraseRaw -> [frequency, charLength]
        Map<String, Integer> ids = new HashMap<>(); // word -> id in the compact sequence

        // Compress the token stream into a word-only sequence, remembering for every word its
        // index in the original token list. A candidate phrase must consist of CONSECUTIVE
        // words in the original text (only separator atoms may sit between them); otherwise
        // the replacement pass could never apply it. This also lets phrases span across
        // ". " separators ("... в тексте. это ..."), which the old WORD-segment split missed.
        // A run of candidate words continues across separator atoms whose text is ONLY
        // whitespace (space/tab/newline): those are exactly the separators the replacement
        // pass tolerates when it matches a phrase token-by-token. Punctuation separators
        // (". ", ", ", ":"), escape atoms, emoji and masked (comment) positions still break
        // the run, so every extracted phrase raw is an exact concatenation of consecutive
        // chunks of the original text and round-trips losslessly through the dictionary.
        List<Integer> wordPos = new ArrayList<>();
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            SaberTokenizer.Token t = tokens.get(i);
            if (t.type() == SaberTokenizer.TokenType.WORD
                    && (maskedToken == null || !maskedToken[i])) {
                seq.add(ids.computeIfAbsent(t.text(), w -> ids.size()));
                wordPos.add(i);
                continue;
            }
            boolean whitespaceBridge = t.type() == SaberTokenizer.TokenType.SEPARATOR
                    && (maskedToken == null || !maskedToken[i])
                    && isWhitespaceOnly(t.text());
            if (!whitespaceBridge) {
                flushWordRun(seq, wordPos, tokens, found);
                seq.clear();
                wordPos.clear();
            }
        }
        flushWordRun(seq, wordPos, tokens, found);

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

    /** True when the atom text consists exclusively of whitespace characters. */
    private static boolean isWhitespaceOnly(String s) {
        for (int k = 0; k < s.length(); k++) {
            if (!Character.isWhitespace(s.charAt(k))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** Space-joined raw of {@code count} words starting at word index {@code base} of a run. */
    private static String joinedWordRawAt(List<SaberTokenizer.Token> tokens, List<Integer> wordPos,
                                          int base, int count) {
        StringBuilder sb = new StringBuilder();
        for (int k = base; k < base + count; k++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tokens.get(wordPos.get(k)).text());
        }
        return sb.toString();
    }

    /** Runs suffix-array phrase extraction over one run of consecutive WORD tokens. */
    private void flushWordRun(List<Integer> seqIds, List<Integer> wordPos,
                              List<SaberTokenizer.Token> tokens, Map<String, int[]> found) {
        if (seqIds.size() < settings.minPhraseLength()) {
            return;
        }
        int[] s = new int[seqIds.size()];
        for (int k = 0; k < s.length; k++) {
            s[k] = seqIds.get(k);
        }
        extractPhrasesFromWords(s, wordPos, tokens, found);
    }

    /** Suffix-array + LCP phrase extraction within one run of consecutive WORD atoms. */
    private void extractPhrasesFromWords(int[] seq, List<Integer> wordPos,
                                         List<SaberTokenizer.Token> tokens,
                                         Map<String, int[]> found) {
        int len = seq.length;
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
                String raw = joinedWordRawAt(tokens, wordPos, base, p);
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
    // (removed: baseline seeding now feeds wordToCode/phraseToCode directly)

    /** Total savings of a candidate at step-1 code length: S = F*(L-C) - C with C = 1. */
    private static long totalSavings(Candidate c) {
        return savingsNew(c.frequency(), c.charLength(), 1);
    }


    /** Max token count among current pool candidates (simulation window bound). */
    private static int maxTokenCount(Map<String, Candidate> pool) {
        int m = 1;
        for (Candidate c : pool.values()) {
            m = Math.max(m, c.tokenCount());
        }
        return m;
    }

    /** True if {@code sub} occurs in {@code sup} as a contiguous token slice. */
    private static boolean containsTokenSequence(String[] sup, String[] sub) {
        outer:
        for (int s = 0; s + sub.length <= sup.length; s++) {
            for (int k = 0; k < sub.length; k++) {
                if (!sup[s + k].equals(sub[k])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Applicability check for phrase candidates: returns true only when the word sequence
     * occurs in the token stream exactly the way the replacement pass can match it - as a
     * span of WORD atoms separated exclusively by SEPARATOR atoms (any ESCAPE_ATOM or EMOJI
     * inside the span blocks the match). Phrases glued across a NEWLINE/emoji by the
     * space-joined raw representation are NOT applicable and must never enter the pool:
     * they would be registered as dead codes and pull their working sub-phrases down in
     * the prune cascade.
     */
    private static boolean isApplicablePhrase(List<SaberTokenizer.Token> tokens, String[] words) {
        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            if (tokens.get(i).type() != SaberTokenizer.TokenType.WORD) {
                continue;
            }
            int end = wordSequenceEndsHere(tokens, i, words);
            if (end >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * End index (exclusive) of the token span starting at WORD position {@code start} whose
     * WORD atoms spell out {@code words} exactly (separators between them allowed), or -1.
     */
    private static int wordSequenceEndsHere(List<SaberTokenizer.Token> tokens, int start, String[] words) {
        int w = 0;
        int n = tokens.size();
        int i = start;
        while (i < n) {
            SaberTokenizer.Token t = tokens.get(i);
            if (t.type() == SaberTokenizer.TokenType.SEPARATOR) {
                i++;
                continue;
            }
            if (t.type() != SaberTokenizer.TokenType.WORD) {
                return -1; // escape atom / emoji breaks the contiguity
            }
            if (!t.text().equals(words[w])) {
                return -1;
            }
            w++;
            i++;
            if (w == words.length) {
                return i;
            }
        }
        return -1;
    }

    /** A simulated stream element: either a raw literal piece or a collapsed coded WORD token. */
    private record SimToken(boolean isWord, String raw, String code) {}

    /**
     * Builds the simulated token stream after applying all registered entries exactly the
     * way the real replacement pass does (chooseReplacement with ignoreGain=true, so every
     * registration is counted as used at least once). Matches collapse into a single
     * WORD-like token whose raw is the space-joined value; unregistered content stays
     * verbatim. Separator atoms between words make phrase raws never match across them,
     * which mirrors the tokenizer's WORD-segment structure.
     */
    private SimToken[] simulateCodes(List<SaberTokenizer.Token> tokens,
                                     Map<String, String> wordToCode,
                                     Map<String, String> phraseToCode,
                                     int maxTokens) {
        List<SimToken> out = new ArrayList<>(tokens.size());
        int n = tokens.size();
        int i = 0;
        while (i < n) {
            SaberTokenizer.Token t = tokens.get(i);
            if (t.type() == SaberTokenizer.TokenType.WORD) {
                boolean skipWord = maskedToken != null && i < maskedToken.length && maskedToken[i];
                int bestLen = 0;
                String bestRaw = null;
                String bestCode = null;
                // Longest registered entry wins at every position (mirrors chooseReplacement).
                // A candidate phrase is a slice of consecutive WORD atoms; when the tokenizer
                // emits separators as their own atoms ("тексте. " -> WORD + SEPARATOR), the
                // raw string of such a slice still contains no separator chars, so we compare
                // token-by-token instead of joining raws and doing an exact map lookup.
                int maxP = Math.min(maxTokens, n - i);
                for (int p = maxP; p >= 2 && !skipWord; p--) {
                    String pc = matchPhraseAt(tokens, i, p, phraseToCode);
                    if (pc != null) {
                        bestLen = p;
                        bestRaw = joinedWordRaw(tokens, i, p);
                        bestCode = pc;
                        break;
                    }
                }
                if (bestLen == 0 && !skipWord) {
                    String wCode = wordToCode.get(t.text());
                    if (wCode != null) {
                        bestLen = 1;
                        bestRaw = t.text();
                        bestCode = wCode;
                    }
                }
                if (bestLen > 0) {
                    out.add(new SimToken(true, bestRaw, bestCode));
                    i += bestLen;
                    continue;
                }
            }
            out.add(new SimToken(false, t.text(), null));
            i++;
        }
        return out.toArray(new SimToken[0]);
    }

    /** Counts occurrences of a candidate raw (space-joined word sequence) in the simulated stream. */
    private static int countRawOccurrences(SimToken[] sim, String raw) {
        String[] parts = raw.split(" ");
        int cnt = 0;
        int n = sim.length;
        outer:
        for (int s = 0; s < n; s++) {
            for (int k = 0; k < parts.length; k++) {
                int idx = s + k;
                if (idx >= n) {
                    break outer;
                }
                SimToken st = sim[idx];
                // exact match on collapsed coded tokens and plain WORD atoms; separator /
                // escape / emoji atoms can never be part of a word sequence
                if (!st.raw().equals(parts[k])) {
                    continue outer;
                }
            }
            cnt++;
        }
        return cnt;
    }

    /** Raw string of the entry actually matched at position {@code i} with token length {@code len}. */
    private static String matchedRaw(List<SaberTokenizer.Token> tokens, int i, int len,
                                    Map<String, String> wordToCode, Map<String, String> phraseToCode) {
        String one = tokens.get(i).text();
        if (len == 1 && wordToCode.containsKey(one)) {
            return one;
        }
        return joinedWordRaw(tokens, i, len);
    }

    /**
     * Space-joined raw value of a candidate phrase starting at token {@code start}: only
     * WORD atoms contribute their text, separator atoms inside the span are skipped. This
     * makes phrase raws independent of how the tokenizer splits "word. word" into atoms.
     */
    private static String joinedWordRaw(List<SaberTokenizer.Token> tokens, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < start + count && k < tokens.size(); k++) {
            SaberTokenizer.Token t = tokens.get(k);
            if (t.type() != SaberTokenizer.TokenType.WORD) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.text());
        }
        return sb.toString();
    }

    /**
     * Token-level lookup of a registered phrase: checks whether the span [i, i+span)
     * consists (modulo separator atoms) of exactly the words of some phrase key. Returns
     * the code of the matching entry or null.
     */
    private static String matchPhraseAt(List<SaberTokenizer.Token> tokens, int i, int span,
                                        Map<String, String> phraseToCode) {
        if (phraseToCode.isEmpty()) {
            return null;
        }
        if (i >= tokens.size() || tokens.get(i).type() != SaberTokenizer.TokenType.WORD) {
            return null; // a phrase match must START on a WORD atom at position i
        }
        int wordIdx = 0;
        String firstWord = null;
        for (int k = i; k < i + span; k++) {
            SaberTokenizer.Token t = tokens.get(k);
            if (t.type() != SaberTokenizer.TokenType.WORD) {
                continue;
            }
            if (wordIdx == 0) {
                firstWord = t.text();
            }
            wordIdx++;
        }
        if (wordIdx < 2 || firstWord == null) {
            return null;
        }
        for (Map.Entry<String, String> e : phraseToCode.entrySet()) {
            String[] words = e.getKey().split(" ");
            if (words.length != wordIdx || !words[0].equals(firstWord)) {
                continue;
            }
            int w = 0;
            boolean ok = true;
            for (int k = i; k < i + span && ok; k++) {
                SaberTokenizer.Token t = tokens.get(k);
                if (t.type() != SaberTokenizer.TokenType.WORD) {
                    continue;
                }
                if (!t.text().equals(words[w])) {
                    ok = false;
                }
                w++;
            }
            if (ok) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Widest atom span (start..end inclusive distance) of an exact occurrence of {@code raw}. */
    private static int maxSpanAtoms(List<SaberTokenizer.Token> tokens, String raw) {
        String[] words = raw.split(" ");
        int best = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() != SaberTokenizer.TokenType.WORD) {
                continue;
            }
            int end = wordSequenceEndsHere(tokens, i, words);
            if (end >= 0) {
                best = Math.max(best, end - i);
            }
        }
        return Math.max(best, words.length);
    }

    /** Atom span consumed by the occurrence of {@code raw} starting at token {@code start}, or -1. */
    private static int spanAtomsAt(List<SaberTokenizer.Token> tokens, int start, String raw) {
        return wordSequenceEndsHere(tokens, start, raw.split(" "));
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
                                    Map<String, String> phraseToCode,
                                    boolean ignoreGain) {
        int n = tokens.size();
        int bestLen = 0;
        long bestGain = 0;
        // Word candidate
        SaberTokenizer.Token t = tokens.get(i);
        if (t.type() == SaberTokenizer.TokenType.WORD && wordToCode.containsKey(t.text())) {
            long gain = t.text().length() - 1L;
            if (ignoreGain || gain > bestGain) {
                bestGain = gain;
                bestLen = 1;
            }
        }
        // Phrase candidates: longest-first, first positive gain wins (longer => bigger gain).
        // Match token-by-token (separators inside the span are tolerated), mirroring
        // simulateCodes; the phrase raw is the space-joined word sequence.
        if (phraseMaxTokens > 0) {
            for (int p = Math.min(phraseMaxTokens, n - i); p >= settings.minPhraseLength(); p--) {
                if (i + p > n) {
                    continue;
                }
                String pc = matchPhraseAt(tokens, i, p, phraseToCode);
                if (pc != null) {
                    long gain = joinedWordRaw(tokens, i, p).length() - 1L;
                    if (ignoreGain || gain > bestGain) {
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

    private boolean[] maskedToken;

    /**
     * Locates comment ranges in the token stream (Technical Specification 3.2.1, 12.4):
     * a "//" marker opens a line comment that runs to the end of the line; a "/* ... *&#47;"
     * pair delimits a block comment. Each range is {@code [startInclusive, endExclusive)}
     * in token indices and covers every atom of the comment, markers included. The marker
     * itself may share an atom with adjacent characters (e.g. the separator atom "// ").
     */
    private static List<int[]> findCommentRanges(List<SaberTokenizer.Token> tokens) {
        List<int[]> ranges = new ArrayList<>();
        int n = tokens.size();
        int i = 0;
        while (i < n) {
            String text = tokens.get(i).text();
            if (text.contains("//")) {
                int start = i;
                while (i < n && !containsNewline(tokens.get(i).text())) {
                    i++;
                }
                if (i < n) {
                    i++; // include the newline atom itself
                }
                ranges.add(new int[]{start, i});
            } else if (text.contains("/*")) {
                int start = i;
                while (i < n && !endsWith(tokens.get(i).text(), "*/")) {
                    i++;
                }
                i = Math.min(n, i + 1); // include the closing delimiter atom
                ranges.add(new int[]{start, i});
            } else {
                i++;
            }
        }
        return ranges;
    }

    private static boolean endsWith(String s, String suffix) {
        return s.endsWith(suffix);
    }

    private static boolean containsNewline(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }

    /** Builds a per-token mask: true for every token index inside one of {@code ranges}. */
    private static boolean[] buildMask(List<int[]> ranges, int size) {
        boolean[] mask = new boolean[size];
        for (int[] r : ranges) {
            for (int k = r[0]; k < r[1] && k < size; k++) {
                mask[k] = true;
            }
        }
        return mask;
    }

    private Map<String, Integer> countWords(List<SaberTokenizer.Token> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            SaberTokenizer.Token t = tokens.get(i);
            if (t.type() == SaberTokenizer.TokenType.WORD
                    && (maskedToken == null || !maskedToken[i])) {
                freq.merge(t.text(), 1, Integer::sum);
            }
        }
        return freq;
    }
}
