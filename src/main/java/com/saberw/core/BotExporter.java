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
        StringBuilder sb = new StringBuilder();
        if (firstMessage) {
            sb.append(load(T_BASE)).append("\n\n");
            if (normalized) {
                sb.append(load(T_NORMALIZATION)).append("\n\n");
            }
            appendDictLines(sb, dictionary.entries(), true);
        } else {
            List<DictEntry> newEntries = dictionary.entries().stream()
                    .filter(en -> !en.sentToBot()).toList();
            if (newEntries.isEmpty()) {
                sb.append("Сообщение:\n___\n");
            } else {
                sb.append(load(T_MINIMAL)).append("\n\n");
                sb.append("Дополнение словаря:\n");
                appendDictLines(sb, newEntries, false);
                sb.append("___\n");
            }
        }
        sb.append(compressed);
        return sb.toString();
    }

    /** Marks every entry as already sent to the bot (returns a new dictionary). */
    public static Dictionary markAllSent(Dictionary dictionary) {
        Dictionary updated = new Dictionary();
        for (DictEntry en : dictionary.entries()) {
            updated.put(en.withSentToBot(true));
        }
        return updated;
    }

    private static void appendDictLines(StringBuilder sb, List<DictEntry> entries, boolean withSeparator) {
        for (DictEntry en : entries) {
            sb.append(en.code()).append('\t')
              .append(en.value().tokens().get(0).text()).append('\n');
        }
        if (withSeparator) {
            sb.append("___\n");
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
