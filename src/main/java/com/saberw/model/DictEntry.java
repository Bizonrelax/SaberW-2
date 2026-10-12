package com.saberw.model;

/** One dictionary record (see Technical Specification §17.1, "entries" item). Record, no logic beyond storage. */
public record DictEntry(
        String code,
        EntryValue value,
        int level,
        int frequency,
        int codeAppliedCount,
        String addedAt,
        boolean sentToBot) {

    public DictEntry withApplied(int applications) {
        return new DictEntry(code, value, level, frequency, codeAppliedCount + applications, addedAt, sentToBot);
    }

    public DictEntry withSentToBot(boolean sent) {
        return new DictEntry(code, value, level, frequency, codeAppliedCount, addedAt, sent);
    }
}
