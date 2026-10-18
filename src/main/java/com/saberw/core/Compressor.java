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

    /** Step-2 gate (TZ 7, 9.2): a step-2 code may only be issued at frequency >= minFrequencyForStep2. */
    static boolean eligibleForStep2(int frequency, CompressionSettings s) {
        return frequency >= s.minFrequencyForStep2();
    }

    /** A compression candidate: a raw string plus its frequency, character length and token count. Mutable: F/S are recomputed each greedy iteration. */
    static final class Candidate {
        final String raw;
        final int charLength;
        final int tokenCount;
        /** Atom span of an occurrence in the token stream (words + separators between them). */
        int spanTokens;
        /** Token index of the last start position seen for this raw (exact-span bookkeeping). */
        int lastStart = -1;
        /** WORD texts of a phrase candidate, in order (empty for word candidates). */
        String[] words = new String[0];
        int frequency;
        long savings;

        Candidate(String raw, int frequency, int charLength, int tokenCount) {
            this.raw = raw;
            this.frequency = frequency;
            this.charLength = charLength;
            this.tokenCount = tokenCount;
            this.spanTokens = tokenCount; // exact atom span is computed in compress() from the stream
        }

        int spanTokens() { return spanTokens; }

        String[] words() { return words; }

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
        List<int[]> commentRanges = settings.ignoreComments() ? findCommentRanges(tokens) : List.of();
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
            // ТЗ п.7 (R-003): нейтральные кандидаты с S == thresholdS допускаются;
            // добавочный гейт `> 0` убран — S=0 живёт, отбрасывается только S < порога.
            if (savingsNew(f, l, 1) >= settings.thresholdS()) {
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
        // candidate contains it as a contiguous WORD slice. Phrase raws are EXACT ranges of
        // the original text (words and every separator atom between them concatenated), so a
        // word-slice containment check needs the words of each raw; those come from the
        // per-run extraction (candidate.tokenCount() counts WORD atoms, separators included
        // in charLength only). Applicability is guaranteed by construction: every extracted
        // raw equals joinRaw(tokens, lastStart, spanTokens) - an exact concatenation of
        // consecutive chunks of the stream - so the replacement pass can always match it.
        List<Candidate> sortedByTokensDesc = new ArrayList<>(phraseCandidates);
        sortedByTokensDesc.sort(Comparator.comparingInt(Candidate::tokenCount).reversed());
        Map<String, String[]> wordsOfRaw = new HashMap<>();
        for (Candidate c : phraseCandidates) {
            wordsOfRaw.put(c.raw(), c.words());
        }
        List<Candidate> maximalPhrases = new ArrayList<>();
        for (Candidate c : sortedByTokensDesc) {
            String[] parts = c.words();
            boolean covered = false;
            for (Candidate longer : maximalPhrases) {
                if (longer.tokenCount() <= c.tokenCount()) {
                    break; // sorted desc: nothing further can strictly contain c's length
                }
                if (containsTokenSequence(longer.words(), parts)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
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
        // Exact-span candidates carry their atom span directly (spanTokens/lastStart were
        // recorded during extraction), so no re-scan of the stream is needed here.
        int maxPhraseTokens = settings.minPhraseLength();
        for (Candidate c : phraseCandidates) {
            maxPhraseTokens = Math.max(maxPhraseTokens, c.spanTokens);
        }
        for (String p : phraseToCode.keySet()) {
            maxPhraseTokens = Math.max(maxPhraseTokens, p.split(" ").length);
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
                // ТЗ п.7 (R-003): doomed = частота ниже minFrequency ИЛИ S строго ниже порога;
                // нейтральные кандидаты с S == thresholdS остаются в пуле.
                if (c.frequency() < settings.minFrequency() || c.savings() < settings.thresholdS()) {
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
            // Step-2 frequency gate (TZ 7, 9.2): a code from step 2 may only be issued when
            // the winner's frequency reaches minFrequencyForStep2; otherwise the candidate is
            // dropped from the pool without registration and the iteration restarts.
            if (allocator.nextIsStep2(takenCodes) && !eligibleForStep2(winner.frequency(), settings)) {
                remaining.remove(winner.raw());
                continue;
            }
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
                // Exact-range semantics: the consumed value is the verbatim concatenation of
                // the atoms in the matched span (words AND separators), identical to the raw
                // stored in the dictionary and used as an appliedCount key.
                String raw = joinRaw(tokens, i, len);
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
     * Finds repeated phrases using a suffix-array + LCP approach over the WORD-id stream
     * (Technical Specification 3.2.3). Returns candidates that satisfy the savings formula
     * for step-1 codes. Exact-range semantics: every candidate raw is the verbatim
     * concatenation of the consecutive token chunks of one occurrence (words AND the
     * separator atoms between them), so dictionary values round-trip separators losslessly.
     */
    List<Candidate> findPhrases(List<SaberTokenizer.Token> tokens) {
        if (settings.minPhraseLength() < 2) {
            return List.of(); // phrase pass disabled by settings
        }
        Map<String, int[]> found = new LinkedHashMap<>(); // phraseRaw -> [frequency, charLength, wordCount]
        Map<String, Integer> ids = new HashMap<>(); // word -> id in the compact sequence
        Set<String> seenInRun = new HashSet<>(); // raws already counted for the current run

        // Compress the token stream into a word-only sequence, remembering for every word its
        // index in the original token list. A run of candidate words continues across
        // separator atoms (any separator - " ", ". ", ", ", "\n" - keeps the run alive);
        // ESCAPE_ATOM / EMOJI atoms and masked (comment) positions terminate it. Every slice
        // of the run therefore maps to an exact contiguous range of the original stream, and
        // the extracted raw preserves the real separators of that range.
        List<Integer> wordPos = new ArrayList<>();
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            SaberTokenizer.Token t = tokens.get(i);
            boolean masked = maskedToken != null && i < maskedToken.length && maskedToken[i];
            if (!masked && t.type() == SaberTokenizer.TokenType.WORD) {
                seq.add(ids.computeIfAbsent(t.text(), w -> ids.size()));
                wordPos.add(i);
                continue;
            }
            if (masked || t.type() != SaberTokenizer.TokenType.SEPARATOR) {
                flushWordRun(seq, wordPos, tokens, found, seenInRun);
                seq.clear();
                wordPos.clear();
                seenInRun.clear();
            }
        }
        flushWordRun(seq, wordPos, tokens, found, seenInRun);

        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : found.entrySet()) {
            int f = e.getValue()[0];
            int l = e.getValue()[1];
            int tokensCnt = e.getValue()[2];
            // ТЗ п.7 (R-003): гейт `> 0` заменён на `>= thresholdS` — нейтральные фразы S=0 живут.
            if (f >= settings.minFrequency() && savingsNew(f, l, 1) >= settings.thresholdS()) {
                Candidate c = new Candidate(e.getKey(), f, l, tokensCnt);
                // WORD texts of the raw (for the maximal-phrase filter): re-tokenize the
                // exact range - words are non-whitespace runs inside it.
                c.words = splitWords(e.getKey());
                // Exact atom span and last start position from the token stream.
                int[] sl = spanAndLastStart(tokens, c.words);
                c.spanTokens = Math.max(sl[0], tokensCnt);
                c.lastStart = sl[1];
                c.frequency = f;
                result.add(c);
            }
        }
        return result;
    }

    /** Non-whitespace runs of an exact phrase raw, in order. */
    private static String[] splitWords(String raw) {
        List<String> ws = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < raw.length(); k++) {
            char ch = raw.charAt(k);
            if (Character.isWhitespace(ch)) {
                if (cur.length() > 0) {
                    ws.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            ws.add(cur.toString());
        }
        return ws.toArray(new String[0]);
    }

    /** Widest atom span of an exact occurrence of {@code words} and the last start index seen. */
    private static int[] spanAndLastStart(List<SaberTokenizer.Token> tokens, String[] words) {
        int best = -1;
        int last = -1;
        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            if (tokens.get(i).type() != SaberTokenizer.TokenType.WORD) {
                continue;
            }
            int end = wordSequenceEndsHere(tokens, i, words);
            if (end >= 0) {
                best = Math.max(best, end - i);
                last = i;
            }
        }
        return new int[]{best, last};
    }

    /** Runs suffix-array phrase extraction over one run of consecutive WORD atoms. */
    private void flushWordRun(List<Integer> seqIds, List<Integer> wordPos,
                              List<SaberTokenizer.Token> tokens, Map<String, int[]> found,
                              Set<String> seenInRun) {
        if (seqIds.size() < settings.minPhraseLength()) {
            return;
        }
        int[] s = new int[seqIds.size()];
        for (int k = 0; k < s.length; k++) {
            s[k] = seqIds.get(k);
        }
        extractPhrasesFromWords(s, wordPos, tokens, found, seenInRun);
    }

    /** Suffix-array + LCP phrase extraction within one run of consecutive WORD atoms. */
    private void extractPhrasesFromWords(int[] seq, List<Integer> wordPos,
                                         List<SaberTokenizer.Token> tokens,
                                         Map<String, int[]> found, Set<String> seenInRun) {
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

        // For every minimum phrase length, group suffixes sharing an LCP >= p and aggregate
        // EXACT ranges: each suffix start maps to the token span [wordPos[start],
        // endOfWords(start+p)), whose raw is the verbatim concatenation of those atoms.
        // Occurrences whose separators differ produce different raws (per-raw frequency),
        // which is exactly the precise-range semantics the replacement pass consumes.
        // MVP practical cap per ТЗ 8.2.6: protects against cubic blowup on periodic texts
        // (long runs x all suffixes x StringBuilder concatenation caused OOM on ~1500 words).
        int pMax = Math.min(len, 64);
        for (int p = settings.minPhraseLength(); p <= pMax; p++) {
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
                // Skip groups that cannot reach minFrequency even counting every start.
                if (j - groupStart < settings.minFrequency()) {
                    k = j;
                    continue;
                }
                // Aggregate EXACT ranges within the LCP group: two occurrences of the same
                // word sequence with different separators (". " vs " ") are DIFFERENT raws,
                // so each distinct verbatim range concatenation gets its own frequency count.
                Map<String, Integer> rawFreq = new HashMap<>();
                for (int g = groupStart; g < j; g++) {
                    int start = sa[g];
                    String raw = exactRawAt(tokens, wordPos, start, p);
                    if (raw == null) {
                        continue; // run ends at the stream tail before the full span
                    }
                    rawFreq.merge(raw, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> rf : rawFreq.entrySet()) {
                    if (rf.getValue() < settings.minFrequency()) {
                        continue; // this exact range repeats too rarely to code
                    }
                    String raw = rf.getKey();
                    int charLen = raw.length();
                    if (charLen >= settings.minWordLength() && seenInRun.add(raw)) {
                        // [frequency, charLength, wordCount]
                        found.put(raw, new int[]{rf.getValue(), charLen, p});
                    }
                }
                k = j;
            }
        }
    }

    /**
     * Exact raw of the range covering {@code count} words starting at word index
     * {@code base} of a run: verbatim concatenation of every atom from the first WORD
     * through the last WORD of the slice (separators included). Null when the slice
     * runs past the end of the token list.
     */
    private static String exactRawAt(List<SaberTokenizer.Token> tokens, List<Integer> wordPos,
                                     int base, int count) {
        int startAtom = wordPos.get(base);
        int lastWordAtom = wordPos.get(base + count - 1);
        if (lastWordAtom >= tokens.size()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int a = startAtom; a <= lastWordAtom; a++) {
            sb.append(tokens.get(a).text());
        }
        return sb.toString();
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

    /** A simulated stream element: either a raw literal piece or a collapsed coded span. */
    private record SimToken(boolean isWord, String raw, String code) {}

    /**
     * Builds the simulated token stream after applying all registered entries exactly the
     * way the real replacement pass does (chooseReplacement with ignoreGain=true, so every
     * registration is counted as used at least once). Matches collapse into a single WORD-like
     * token whose raw is the VERBATIM concatenation of the matched atoms (exact-range
     * semantics); unregistered content stays verbatim. Because every chunk of the simulated
     * stream is an exact piece of the original text, counting occurrences of a candidate raw
     * reduces to substring-style matching over concatenated chunk sequences.
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
                // Matching is EXACT: the phrase key must equal joinRaw(tokens, i, p), the
                // verbatim concatenation of the candidate atom span (words and separators).
                int maxP = Math.min(maxTokens, n - i);
                for (int p = maxP; p >= settings.minPhraseLength() && !skipWord; p--) {
                    String pc = matchPhraseExact(tokens, i, p, phraseToCode);
                    if (pc != null) {
                        bestLen = p;
                        bestRaw = joinRaw(tokens, i, p);
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

    /** Counts occurrences of an exact raw in the simulated stream via chunk concatenation. */
    private static int countRawOccurrences(SimToken[] sim, String raw) {
        int cnt = 0;
        int n = sim.length;
        outer:
        for (int s = 0; s < n; s++) {
            StringBuilder sb = new StringBuilder();
            for (int k = s; k < n; k++) {
                String chunk = sim[k].raw();
                if (sb.length() + chunk.length() > raw.length()) {
                    continue outer;
                }
                sb.append(chunk);
                if (sb.length() == raw.length()) {
                    if (sb.toString().equals(raw)) {
                        cnt++;
                    }
                    continue outer;
                }
            }
        }
        return cnt;
    }

    /** Verbatim concatenation of the atom texts in [start, start+count). */
    private static String joinRaw(List<SaberTokenizer.Token> tokens, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < start + count && k < tokens.size(); k++) {
            sb.append(tokens.get(k).text());
        }
        return sb.toString();
    }

    /**
     * Exact phrase lookup: returns the code of the registered phrase whose key equals the
     * verbatim concatenation of the atoms in [i, i+span), or null. The span must begin on a
     * WORD atom and contain at least two words (separators between them are part of the raw).
     */
    private static String matchPhraseExact(List<SaberTokenizer.Token> tokens, int i, int span,
                                           Map<String, String> phraseToCode) {
        if (phraseToCode.isEmpty()) {
            return null;
        }
        if (i >= tokens.size() || tokens.get(i).type() != SaberTokenizer.TokenType.WORD) {
            return null; // a phrase match must START on a WORD atom at position i
        }
        int wordCount = 0;
        for (int k = i; k < i + span && k < tokens.size(); k++) {
            if (tokens.get(k).type() == SaberTokenizer.TokenType.WORD) {
                wordCount++;
            } else if (tokens.get(k).type() != SaberTokenizer.TokenType.SEPARATOR) {
                return null; // escape/emoji atoms can never sit inside a matched phrase span
            }
        }
        if (wordCount < 2) {
            return null;
        }
        return phraseToCode.get(joinRaw(tokens, i, span));
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
        // EXACT matching (verbatim span concatenation), mirroring simulateCodes; the phrase
        // raw consumed by the replacement pass is joinRaw(tokens, i, p) - identical key.
        if (phraseMaxTokens > 0) {
            for (int p = Math.min(phraseMaxTokens, n - i); p >= settings.minPhraseLength(); p--) {
                if (i + p > n) {
                    continue;
                }
                String pc = matchPhraseExact(tokens, i, p, phraseToCode);
                if (pc != null) {
                    long gain = joinRaw(tokens, i, p).length() - 1L;
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

    private static final String[] PREPROCESSOR_KEYWORDS = {
            "include", "define", "undef", "if", "ifdef", "ifndef",
            "elif", "else", "endif", "line", "error", "pragma"};

    private static List<int[]> findCommentRanges(List<SaberTokenizer.Token> tokens) {
        List<int[]> ranges = new ArrayList<>();
        int n = tokens.size();
        int i = 0;
        while (i < n) {
            String text = tokens.get(i).text();
            int html = text.indexOf("<!--");
            int block = text.indexOf("/*");
            int line = indexOfLineMarker(text);
            int hash = indexOfHashMarker(text, tokens, i);
            int best = -1;
            char kind = 0;
            if (html >= 0) { best = html; kind = 'h'; }
            if (block >= 0 && (best < 0 || block < best)) { best = block; kind = 'b'; }
            if (line >= 0 && (best < 0 || line < best)) { best = line; kind = 'l'; }
            if (hash >= 0 && (best < 0 || hash < best)) { best = hash; kind = 'x'; }
            if (kind == 0) { i++; continue; }
            int start = i;
            if (kind == 'l' || kind == 'x') {
                while (i < n && !containsNewline(tokens.get(i).text())) i++;
                if (i < n) i++;
            } else if (kind == 'b') {
                while (i < n && !endsWith(tokens.get(i).text(), "*/")) i++;
                i = Math.min(n, i + 1);
            } else {
                while (i < n && !tokens.get(i).text().contains("-->")) i++;
                i = Math.min(n, i + 1);
            }
            ranges.add(new int[]{start, i});
        }
        return ranges;
    }

    /** Две косые черты: НЕ открывают комментарий, если непосредственно перед ними двоеточие (защита URL, ТЗ 12.2). */
    private static int indexOfLineMarker(String atom) {
        int from = 0;
        while (true) {
            int idx = atom.indexOf("//", from);
            if (idx < 0) return -1;
            if (idx == 0 || atom.charAt(idx - 1) != ':') return idx;
            from = idx + 1;
        }
    }

    /** Решётка: НЕ открывает комментарий, если стоит первым непробельным символом строки и за ней идёт ключевое слово C-препроцессора (директива, ТЗ 12.2). */
    private static int indexOfHashMarker(String atom, List<SaberTokenizer.Token> tokens, int i) {
        int idx = atom.indexOf('#');
        if (idx < 0) return -1;
        int nl = atom.lastIndexOf('\n', idx);
        boolean atLineStart;
        if (nl >= 0) {
            atLineStart = atom.substring(nl + 1, idx).isBlank();
        } else {
            atLineStart = atom.substring(0, idx).isBlank()
                    && (i == 0 || containsNewline(tokens.get(i - 1).text()));
        }
        if (atLineStart) {
            String rest = atom.substring(idx + 1).trim();
            for (String kw : PREPROCESSOR_KEYWORDS) {
                if (rest.startsWith(kw)) return -1;
            }
        }
        return idx;
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
