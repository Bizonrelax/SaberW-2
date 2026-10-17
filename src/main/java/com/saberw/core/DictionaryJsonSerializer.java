package com.saberw.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
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
                       CompressionSettings settings) {

        /** Legacy constructor without settings: uses the default (max-cjk) settings. */
        public Root(String id, Instant createdAt, Dictionary dictionary) {
            this(id, createdAt, dictionary, CompressionSettings.maxCjk());
        }

        /** Convenience constructor keeping only the case-normalization flag. */
        public Root(String id, Instant createdAt, Dictionary dictionary, boolean normalizeCase) {
            this(id, createdAt, dictionary, withNormalizeCase(CompressionSettings.maxCjk(), normalizeCase));
        }
    }

    private static CompressionSettings withNormalizeCase(CompressionSettings base, boolean normalizeCase) {
        return new CompressionSettings(base.minWordLength(), base.minFrequency(),
                base.minPhraseLength(), base.thresholdS(), normalizeCase);
    }

    /** Settings key in JSON; absent in older files means default (max-cjk) settings. */
    private static final String SETTINGS_NODE = "settings";

    public String toJson(Root root) throws IOException {
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

        ObjectNode dictNode = node.putObject("dictionary");
        dictNode.put("updatedAt", Instant.now().toString());
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
        meta.put("note", "");
        meta.putArray("chatLinks");
        meta.putArray("appliedDates");
        meta.put("totalCompressions", 0);
        meta.put("totalDecompressions", 0);
        meta.put("supplementCount", 0);
        meta.put("mergeCount", 0);
        meta.put("exportCount", 0);
        meta.put("lastExportIncludedMetadata", false);
        meta.put("sizeBytes", 0);
        meta.put("entriesCount", root.dictionary().size());
        meta.put("totalValueChars", totalValueChars(root.dictionary()));

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
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
                sn.path("ignoreComments").asBoolean(false));

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
        return new Root(id, createdAt, dictionary, settings);
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
