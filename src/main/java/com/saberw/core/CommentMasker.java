package com.saberw.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Base comment heuristic of Technical Specification 3.2.1: locates line/block comments
 * ( {@code // ... \n}, {@code /* ... *&#47;}, {@code <!-- ... -->}, {@code # ... \n} ) inside the
 * SHIELDED text so that the compressor can exclude those regions from frequency counting
 * and from code replacement when {@code ignoreComments} is enabled.
 *
 * <p>Detection is deliberately conservative: a marker only counts when it appears at a
 * token boundary position (start/end of the string or adjacent to a non word-part
 * character), which avoids false positives inside identifiers such as {@code http//x} or
 * {@code a#b}. The shielded text is never modified - the masker returns index ranges.</p>
 */
public final class CommentMasker {

    /** Inclusive-exclusive character range [from, to) of one comment region. */
    public record Range(int from, int to) {}

    private CommentMasker() {
    }

    /** All comment ranges in the given shielded text, left to right, non-overlapping. */
    public static List<Range> findRanges(String shielded) {
        List<Range> ranges = new ArrayList<>();
        int n = shielded.length();
        int i = 0;
        while (i < n) {
            char c = shielded.charAt(i);
            if (c == '/' && i + 1 < n) {
                char d = shielded.charAt(i + 1);
                if (d == '/' && standaloneAt(shielded, i)) {
                    int end = indexOfLineEnd(shielded, i + 2);
                    ranges.add(new Range(i, end));
                    i = end;
                    continue;
                }
                if (d == '*' && standaloneAt(shielded, i)) {
                    int close = shielded.indexOf("*/", i + 2);
                    int end = close < 0 ? n : close + 2;
                    ranges.add(new Range(i, end));
                    i = end;
                    continue;
                }
            }
            if (c == '#' && standaloneAt(shielded, i)) {
                int end = indexOfLineEnd(shielded, i + 1);
                ranges.add(new Range(i, end));
                i = end;
                continue;
            }
            if (c == '<' && shielded.startsWith("<!--", i) && standaloneAt(shielded, i)) {
                int close = shielded.indexOf("-->", i + 4);
                int end = close < 0 ? n : close + 3;
                ranges.add(new Range(i, end));
                i = end;
                continue;
            }
            i++;
        }
        return ranges;
    }

    /** True when every offset in [from, to) belongs to some range. */
    public static boolean overlaps(List<Range> ranges, int from, int to) {
        for (Range r : ranges) {
            if (r.from() < to && from < r.to()) {
                return true;
            }
        }
        return false;
    }

    /** A marker is honoured only at a boundary: not glued to a word-part character before it. */
    private static boolean standaloneAt(String s, int index) {
        if (index == 0) {
            return true;
        }
        char prev = s.charAt(index - 1);
        return !isWordPart(prev) || Shielder.isCjkExtensionA(prev);
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int indexOfLineEnd(String s, int from) {
        int nl = s.indexOf('\n', from);
        return nl < 0 ? s.length() : nl;
    }
}
