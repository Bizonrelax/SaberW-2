package com.saberw.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory dictionary keyed by code (level-1 MVP). */
public final class Dictionary {

    private final Map<String, DictEntry> byCode = new HashMap<>();

    public void put(DictEntry entry) {
        byCode.put(entry.code(), entry);
    }

    public DictEntry get(String code) {
        return byCode.get(code);
    }

    public boolean containsCode(String code) {
        return byCode.containsKey(code);
    }

    public List<DictEntry> entries() {
        return new ArrayList<>(byCode.values());
    }

    /** Removes the entry with the given code (used by the dead-code pruning pass). */
    public void remove(String code) {
        byCode.remove(code);
    }

    public int size() {
        return byCode.size();
    }
}
