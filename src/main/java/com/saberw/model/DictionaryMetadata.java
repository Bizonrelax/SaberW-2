package com.saberw.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Living dictionary metadata (Technical Specification 17.1).
 * Immutable-style POJO: every mutation returns a new instance.
 */
public final class DictionaryMetadata {

    private String note;
    private List<String> chatLinks;
    private List<String> appliedDates;
    private int totalCompressions;
    private int totalDecompressions;
    private int supplementCount;
    private int mergeCount;
    private int exportCount;
    private boolean lastExportIncludedMetadata;
    private long sizeBytes;
    private int entriesCount;
    private int totalValueChars;

    public DictionaryMetadata() {
        this("", new ArrayList<>(), new ArrayList<>());
    }

    public DictionaryMetadata(String note, List<String> chatLinks, List<String> appliedDates) {
        this.note = note == null ? "" : note;
        this.chatLinks = chatLinks == null ? new ArrayList<>() : new ArrayList<>(chatLinks);
        this.appliedDates = appliedDates == null ? new ArrayList<>() : new ArrayList<>(appliedDates);
    }

    /** Copy constructor: produces an independent clone of the given metadata. */
    public DictionaryMetadata(DictionaryMetadata other) {
        this(other.note, other.chatLinks, other.appliedDates);
        this.totalCompressions = other.totalCompressions;
        this.totalDecompressions = other.totalDecompressions;
        this.supplementCount = other.supplementCount;
        this.mergeCount = other.mergeCount;
        this.exportCount = other.exportCount;
        this.lastExportIncludedMetadata = other.lastExportIncludedMetadata;
        this.sizeBytes = other.sizeBytes;
        this.entriesCount = other.entriesCount;
        this.totalValueChars = other.totalValueChars;
    }

    // ---- Getters / setters -------------------------------------------------

    public String note() { return note; }
    public void setNote(String value) { this.note = value == null ? "" : value; }

    public List<String> chatLinks() { return new ArrayList<>(chatLinks); }
    public void setChatLinks(List<String> value) {
        this.chatLinks = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public List<String> appliedDates() { return new ArrayList<>(appliedDates); }
    public void setAppliedDates(List<String> value) {
        this.appliedDates = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public int totalCompressions() { return totalCompressions; }
    public void setTotalCompressions(int value) { this.totalCompressions = value; }

    public int totalDecompressions() { return totalDecompressions; }
    public void setTotalDecompressions(int value) { this.totalDecompressions = value; }

    public int supplementCount() { return supplementCount; }
    public void setSupplementCount(int value) { this.supplementCount = value; }

    public int mergeCount() { return mergeCount; }
    public void setMergeCount(int value) { this.mergeCount = value; }

    public int exportCount() { return exportCount; }
    public void setExportCount(int value) { this.exportCount = value; }

    public boolean lastExportIncludedMetadata() { return lastExportIncludedMetadata; }
    public void setLastExportIncludedMetadata(boolean value) { this.lastExportIncludedMetadata = value; }

    public long sizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { this.sizeBytes = value; }

    public int entriesCount() { return entriesCount; }
    public void setEntriesCount(int value) { this.entriesCount = value; }

    public int totalValueChars() { return totalValueChars; }
    public void setTotalValueChars(int value) { this.totalValueChars = value; }

    // ---- Derived copies (each returns a new instance) ----------------------

    /** Increment compression counter and remember the day (YYYY-MM-DD) without duplicates. */
    public DictionaryMetadata withCompression(String today) {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.totalCompressions++;
        if (today != null && !m.appliedDates.contains(today)) {
            m.appliedDates.add(today);
        }
        return m;
    }

    public DictionaryMetadata withDecompression() {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.totalDecompressions++;
        return m;
    }

    public DictionaryMetadata withSupplement() {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.supplementCount++;
        return m;
    }

    public DictionaryMetadata withMerge() {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.mergeCount++;
        return m;
    }

    /** JSON export: exportCount+1 and lastExportIncludedMetadata becomes the given flag. */
    public DictionaryMetadata withJsonExport(boolean includedMetadata) {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.exportCount++;
        m.lastExportIncludedMetadata = includedMetadata;
        return m;
    }

    /** Bot export: only exportCount+1; lastExportIncludedMetadata is left untouched (TZ 6.8–6.9). */
    public DictionaryMetadata withBotExport() {
        DictionaryMetadata m = new DictionaryMetadata(this);
        m.exportCount++;
        return m;
    }

    /** Recompute entriesCount and totalValueChars from the dictionary (EntryValue.charLength). */
    public DictionaryMetadata recompute(Dictionary dictionary) {
        DictionaryMetadata m = new DictionaryMetadata(this);
        if (dictionary != null) {
            m.entriesCount = dictionary.size();
            int sum = 0;
            for (DictEntry e : dictionary.entries()) {
                sum += e.value().charLength();
            }
            m.totalValueChars = sum;
        }
        return m;
    }
}
