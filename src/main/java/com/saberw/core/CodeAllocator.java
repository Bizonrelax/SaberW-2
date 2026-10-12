package com.saberw.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Code generator (see Technical Specification sections 3.3 and 20).
 * Step 1: single CJK Extension A characters (U+3400..U+4DBF), C = 1.
 * Step 2: prefix codes "§XY" with X,Y in Extension A, C = 3.
 */
public final class CodeAllocator {

    public static final int STEP1_RANGE_START = 0x3400;
    public static final int STEP1_RANGE_END = 0x4DBF;
    public static final int STEP1_CAPACITY = STEP1_RANGE_END - STEP1_RANGE_START + 1; // 6592

    private int step1Cursor = STEP1_RANGE_START;

    /** Allocates the next unused code (skipping codes already taken by an external set). */
    public String next(java.util.Set<String> taken) {
        while (step1Cursor <= STEP1_RANGE_END) {
            String candidate = String.valueOf(Character.toChars(step1Cursor++));
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        // Step 2: prefix codes §XY
        for (int x = STEP1_RANGE_START; x <= STEP1_RANGE_END; x++) {
            for (int y = STEP1_RANGE_START; y <= STEP1_RANGE_END; y++) {
                String candidate = "§" + Character.toString((char) x) + Character.toString((char) y);
                if (!taken.contains(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Code space exhausted");
    }

    public boolean isStep1Exhausted() {
        return step1Cursor > STEP1_RANGE_END;
    }

    /** All step-1 codes as a list (helper for tests/tools). */
    public static List<String> allStep1Codes() {
        List<String> list = new ArrayList<>(STEP1_CAPACITY);
        for (int cp = STEP1_RANGE_START; cp <= STEP1_RANGE_END; cp++) {
            list.add(String.valueOf(Character.toChars(cp)));
        }
        return list;
    }
}
