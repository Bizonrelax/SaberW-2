package com.saberw.core;

import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.EntryValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the text sent to the LLM chat bot from resource templates
 * (see Technical Specification, ДОПОЛНЕНИЕ №1). Lives in core so both GUI and CLI use it.
 * Template resources are loaded via this class' own classloader (works for fat-JARs too).
 */
public final class BotExporter {

    private static final String T_BASE = "templates/bot-block-base.ru.txt";
    private static final String T_NORMALIZATION = "templates/bot-block-normalization.ru.txt";
    private static final String T_MINIMAL = "templates/bot-instruction-minimal.ru.txt";

    private BotExporter() {
    }

    /**
     * @param firstMessage true  — full session start: base block (+ normalization block) + whole dictionary
     *                     false — continuation: minimal instruction + only entries with sentToBot == false
     * @param normalized   whether case normalization was active during compression
     * @param compressed   the compressed message body
     */
    public static String build(Dictionary dictionary, boolean firstMessage, boolean normalized, String compressed) {
        // Pre-compute the block texts (without separator) to pick a collision-free separator (TZ 18.6).
        StringBuilder blocks = new StringBuilder();
        if (firstMessage) {
            blocks.append(load(T_BASE)).append("\n\n");
            if (normalized) {
                blocks.append(load(T_NORMALIZATION)).append("\n\n");
            }
            appendDictLines(blocks, dictionary.entries(), false, "");
        } else {
            List<DictEntry> newEntries = dictionary.entries().stream()
                    .filter(en -> !en.sentToBot()).toList();
            if (!newEntries.isEmpty()) {
                blocks.append(load(T_MINIMAL)).append("\n\n");
                blocks.append("Дополнение словаря:\n");
                appendDictLines(blocks, newEntries, false, "");
            }
        }
        String sep = resolveSeparator(blocks.toString(), "", compressed);
        StringBuilder sb = new StringBuilder();
        if (firstMessage) {
            sb.append(load(T_BASE)).append("\n\n");
            if (normalized) {
                sb.append(load(T_NORMALIZATION)).append("\n\n");
            }
            appendDictLines(sb, dictionary.entries(), true, sep);
        } else {
            List<DictEntry> newEntries = dictionary.entries().stream()
                    .filter(en -> !en.sentToBot()).toList();
            if (newEntries.isEmpty()) {
                sb.append("Сообщение:\n").append(sep).append('\n');
            } else {
                sb.append(load(T_MINIMAL)).append("\n\n");
                sb.append("Дополнение словаря:\n");
                appendDictLines(sb, newEntries, false, sep);
                sb.append(sep).append('\n');
            }
        }
        sb.append(compressed);
        return sb.toString();
    }

    /**
     * Serializes an entry value as the concatenation of ALL its tokens (TZ 10.2),
     * then escapes backslashes, tabs, newlines and carriage returns so that a record
     * always fits on one line and separators cannot be forged (TZ 23.1.8).
     */
    static String serializeValue(EntryValue v) {
        StringBuilder raw = new StringBuilder();
        for (EntryValue.ValueToken t : v.tokens()) {
            if ("code".equals(t.type())) {
                raw.append(t.code());
            } else {
                raw.append(t.text());
            }
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Picks the first block separator candidate that does not collide with the
     * text of any exported block (TZ 18.6).
     */
    static String resolveSeparator(String dictText, String instruction, String compressed) {
        String[] candidates = {"___", "~~~", "###", "===", "***"};
        for (String cand : candidates) {
            if (!dictText.contains(cand) && !instruction.contains(cand) && !compressed.contains(cand)) {
                return cand;
            }
        }
        throw new IllegalStateException("All block separators collide");
    }

    /** Marks every entry as already sent to the bot (returns a new dictionary). */
    public static Dictionary markAllSent(Dictionary dictionary) {
        Dictionary updated = new Dictionary();
        for (DictEntry en : dictionary.entries()) {
            updated.put(en.withSentToBot(true));
        }
        return updated;
    }

    private static void appendDictLines(StringBuilder sb, List<DictEntry> entries, boolean withSeparator, String sep) {
        for (DictEntry en : entries) {
            sb.append(en.code()).append('\t')
              .append(serializeValue(en.value())).append('\n');
        }
        if (withSeparator) {
            sb.append(sep).append('\n');
        }
    }

    private static String load(String resource) {
        try (InputStream is = BotExporter.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return "";
        }
    }
}
