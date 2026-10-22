package com.saberw.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.DictionaryMetadata;
import com.saberw.model.EntryValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON persistence for dictionaries (format "saberw-dictionary", version 1).
 * Serialization logic is separated from the model classes.
 */
public final class DictionaryJsonSerializer {

    public static final String FORMAT = "saberw-dictionary";
    public static final int FORMAT_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    public record Root(String id, Instant createdAt, Dictionary dictionary,
                       CompressionSettings settings, DictionaryMetadata metadata) {

        /** Legacy constructor without settings: uses the default (max-cjk) settings. */
        public Root(String id, Instant createdAt, Dictionary dictionary) {
            this(id, createdAt, dictionary, CompressionSettings.maxCjk());
        }

        /** Convenience constructor keeping only the case-normalization flag. */
        public Root(String id, Instant createdAt, Dictionary dictionary, boolean normalizeCase) {
            this(id, createdAt, dictionary, withNormalizeCase(CompressionSettings.maxCjk(), normalizeCase));
        }

        /** Secondary constructor without metadata: stub counters, recomputed from the dictionary. */
        public Root(String id, Instant createdAt, Dictionary dictionary, CompressionSettings settings) {
            this(id, createdAt, dictionary, settings, new DictionaryMetadata().recompute(dictionary));
        }
    }

    private static CompressionSettings withNormalizeCase(CompressionSettings base, boolean normalizeCase) {
        return new CompressionSettings(base.minWordLength(), base.minFrequency(),
                base.minPhraseLength(), base.thresholdS(), normalizeCase);
    }

    /** Settings key in JSON; absent in older files means default (max-cjk) settings. */
    private static final String SETTINGS_NODE = "settings";

    public String toJson(Root root) throws IOException {
        return toJson(root, Instant.now());
    }

    /**
     * TZ 6.10 + deterministic-time fix: the timestamp is captured ONCE per serialization pass and
     * reused for every date field the serializer emits, so the measured zero-size JSON and the
     * final JSON differ only in the digits of sizeBytes — no Instant.now() race between the two
     * passes. sizeBytes is measured from the SAME single-pass string (the one built with
     * sizeBytes=0), i.e. a single source of truth. Line endings are normalized to LF regardless
     * of the OS default so the byte length is identical on Linux and Windows.
     */
    public String toJson(Root root, Instant fixedNow) throws IOException {
        DictionaryMetadata measured = root.metadata().recompute(root.dictionary());
        // Single source of truth: the value WRITTEN into metadata.sizeBytes is the UTF-8 length
        // of the FINAL string this method returns — i.e. the zero-size variant of THIS EXACT
        // serialization (same fixedNow, same LF, same recomputed counters) plus the digit delta
        // of the unquoted number ("0" placeholder -> N digits changes only those digit bytes,
        // never the line count). The incoming sizeBytes is explicitly zeroed before measuring,
        // because recompute() preserves it and a stale value with a different digit width would
        // inflate the measured string itself. measureSizeBytes solves the same fixed-point
        // equation from the same base length, so an independent recount reproduces the written
        // value exactly and the final string's byte length equals the reported sizeBytes. All
        // passes share one fixedNow, so there is no Instant.now() race between them.
        long baseLen = measureZeroSizedBytes(root, sized(measured, 0), fixedNow);
        long size = baseLen;
        for (int i = 0; i < 3; i++) {
            long next = baseLen + Long.toString(size).length() - 1;
            if (next == size) break;
            size = next;
        }
        return writeRoot(buildNode(root, sized(measured, size), fixedNow));
    }

    private static DictionaryMetadata sized(DictionaryMetadata base, long sizeBytes) {
        DictionaryMetadata copy = new DictionaryMetadata(base);
        copy.setSizeBytes(sizeBytes);
        return copy;
    }

    /**
     * Single source of truth for sizeBytes: serialize the root once with metadata.sizeBytes = 0
     * and return the UTF-8 byte length of that exact string. The value written into sizeBytes by
     * toJson is exactly what this method returns (same fixedNow semantics, same LF normalization),
     * so an independent recount reproduces the identical number.
     */
    public long measureSizeBytes(Root root) throws IOException {
        DictionaryMetadata measured = root.metadata().recompute(root.dictionary());
        return measureZeroSizedBytes(root, sized(measured, 0), Instant.now());
    }

    private long measureZeroSizedBytes(Root root, DictionaryMetadata measured, Instant fixedNow)
            throws IOException {
        DictionaryMetadata zeroed = new DictionaryMetadata(measured);
        zeroed.setSizeBytes(0);
        String zeroSized = writeRoot(buildNode(root, zeroed, fixedNow));
        return zeroSized.getBytes(StandardCharsets.UTF_8).length;
    }

    private String writeRoot(ObjectNode node) throws IOException {
        // Jackson pretty printer uses the JVM default line separator; on Windows CI that is CRLF,
        // which changes the measured UTF-8 byte length vs Linux (LF). Normalize to LF so sizeBytes
        // is deterministic across platforms.
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n");
    }

    private ObjectNode buildNode(Root root, DictionaryMetadata metadata, Instant fixedNow) {
        ObjectNode node = mapper.createObjectNode();
        node.put("format", FORMAT);
        node.put("formatVersion", FORMAT_VERSION);
        node.put("id", root.id());
        node.put("createdAt", root.createdAt().toString());
        ObjectNode settingsNode = node.putObject(SETTINGS_NODE);
        settingsNode.put("minWordLength", root.settings().minWordLength());
        settingsNode.put("minFrequency", root.settings().minFrequency());
        settingsNode.put("minPhraseLength", root.settings().minPhraseLength());
        settingsNode.put("thresholdS", root.settings().thresholdS());
        settingsNode.put("normalizeCase", root.settings().normalizeCase());
        settingsNode.put("ignoreComments", root.settings().ignoreComments());
        settingsNode.put("minFrequencyForStep2", root.settings().minFrequencyForStep2());

        ObjectNode dictNode = node.putObject("dictionary");
        dictNode.put("updatedAt", fixedNow.toString());
        dictNode.put("measureUnit", "characters");
        ArrayNode entries = dictNode.putArray("entries");
        for (DictEntry e : root.dictionary().entries()) {
            ObjectNode en = entries.addObject();
            en.put("code", e.code());
            ArrayNode valueArr = en.putArray("value");
            for (EntryValue.ValueToken t : e.value().tokens()) {
                ObjectNode tn = valueArr.addObject();
                tn.put("type", t.type());
                if (t.text() != null) {
                    tn.put("text", t.text());
                }
                if (t.code() != null) {
                    tn.put("code", t.code());
                }
            }
            en.put("level", e.level());
            en.put("frequency", e.frequency());
            en.put("codeAppliedCount", e.codeAppliedCount());
            en.put("addedAt", e.addedAt());
            en.put("sentToBot", e.sentToBot());
        }

        ObjectNode meta = node.putObject("metadata");
        meta.put("note", metadata.note());
        ArrayNode chatLinks = meta.putArray("chatLinks");
        for (String link : metadata.chatLinks()) {
            chatLinks.add(link);
        }
        ArrayNode appliedDates = meta.putArray("appliedDates");
        for (String date : metadata.appliedDates()) {
            appliedDates.add(date);
        }
        meta.put("totalCompressions", metadata.totalCompressions());
        meta.put("totalDecompressions", metadata.totalDecompressions());
        meta.put("supplementCount", metadata.supplementCount());
        meta.put("mergeCount", metadata.mergeCount());
        meta.put("exportCount", metadata.exportCount());
        meta.put("lastExportIncludedMetadata", metadata.lastExportIncludedMetadata());
        meta.put("sizeBytes", metadata.sizeBytes());
        meta.put("entriesCount", metadata.entriesCount());
        meta.put("totalValueChars", metadata.totalValueChars());

        return node;
    }

    public Root fromJson(String json) throws IOException {
        JsonNode node = mapper.readTree(json);
        if (!FORMAT.equals(node.path("format").asText())) {
            throw new IOException("Unsupported format: " + node.path("format").asText());
        }
        String id = node.path("id").asText();
        Instant createdAt = Instant.parse(node.path("createdAt").asText(Instant.EPOCH.toString()));
        JsonNode sn = node.path(SETTINGS_NODE);
        CompressionSettings defaults = CompressionSettings.maxCjk();
        CompressionSettings settings = new CompressionSettings(
                sn.path("minWordLength").asInt(defaults.minWordLength()),
                sn.path("minFrequency").asInt(defaults.minFrequency()),
                sn.path("minPhraseLength").asInt(defaults.minPhraseLength()),
                sn.path("thresholdS").asInt(defaults.thresholdS()),
                sn.path("normalizeCase").asBoolean(defaults.normalizeCase()),
                sn.path("ignoreComments").asBoolean(false),
                sn.path("minFrequencyForStep2").asInt(6));

        Dictionary dictionary = new Dictionary();
        for (JsonNode en : node.path("dictionary").path("entries")) {
            List<EntryValue.ValueToken> tokens = new ArrayList<>();
            for (JsonNode tn : en.path("value")) {
                String type = tn.path("type").asText();
                if ("text".equals(type)) {
                    tokens.add(EntryValue.ValueToken.text(tn.path("text").asText()));
                } else {
                    tokens.add(EntryValue.ValueToken.codeRef(tn.path("code").asText()));
                }
            }
            dictionary.put(new DictEntry(
                    en.path("code").asText(),
                    new EntryValue(tokens),
                    en.path("level").asInt(1),
                    en.path("frequency").asInt(0),
                    en.path("codeAppliedCount").asInt(0),
                    en.path("addedAt").asText(""),
                    en.path("sentToBot").asBoolean(false)));
        }

        DictionaryMetadata metadata;
        JsonNode mn = node.path("metadata");
        if (mn.isObject()) {
            metadata = new DictionaryMetadata(
                    mn.path("note").asText(""),
                    readStringList(mn.path("chatLinks")),
                    readStringList(mn.path("appliedDates")));
            metadata.setTotalCompressions(mn.path("totalCompressions").asInt(0));
            metadata.setTotalDecompressions(mn.path("totalDecompressions").asInt(0));
            metadata.setSupplementCount(mn.path("supplementCount").asInt(0));
            metadata.setMergeCount(mn.path("mergeCount").asInt(0));
            metadata.setExportCount(mn.path("exportCount").asInt(0));
            metadata.setLastExportIncludedMetadata(mn.path("lastExportIncludedMetadata").asBoolean(false));
            metadata.setSizeBytes(mn.path("sizeBytes").asLong(0));
            metadata.setEntriesCount(mn.path("entriesCount").asInt(dictionary.size()));
            metadata.setTotalValueChars(mn.path("totalValueChars").asInt(totalValueChars(dictionary)));
            // counters and lists are authoritative; entry-derived fields stay in sync with the dict
            metadata = metadata.recompute(dictionary);
        } else {
            // absent metadata block: stub + recompute
            metadata = new DictionaryMetadata().recompute(dictionary);
        }
        return new Root(id, createdAt, dictionary, settings, metadata);
    }

    private static List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            out.add(item.asText());
        }
        return out;
    }

    public void save(Path file, Root root) throws IOException {
        Files.writeString(file, toJson(root), StandardCharsets.UTF_8);
    }

    public Root load(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static int totalValueChars(Dictionary d) {
        int sum = 0;
        for (DictEntry e : d.entries()) {
            sum += e.value().charLength();
        }
        return sum;
    }
}
