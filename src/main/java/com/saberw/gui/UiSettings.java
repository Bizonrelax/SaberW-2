package com.saberw.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent UI settings stored in a settings-JSON file (TZ 2026-10-19, step 3).
 * JSON read/write goes through the same ObjectMapper used by DictionaryJsonSerializer.
 * Fields: aboutLanguage (String), font.size (int).
 */
public final class UiSettings {

    public static final String KEY_ABOUT_LANGUAGE = "aboutLanguage";
    public static final String KEY_FONT_SIZE = "font.size";
    public static final String DEFAULT_LANGUAGE = "ru";
    public static final int DEFAULT_FONT_SIZE = 14;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public UiSettings(Path file) {
        this.file = file;
    }

    /**
     * Default location: &lt;user.home&gt;/.saberw2/settings.json;
     * if user.home is unavailable, falls back to &lt;cwd&gt;/SaberW2/settings.json.
     */
    public static Path defaultSettingsFile() {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home, ".saberw2", "settings.json");
        }
        return Path.of(System.getProperty("user.dir"), "SaberW2", "settings.json");
    }

    private ObjectNode readRoot() {
        try {
            if (Files.exists(file)) {
                JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (node.isObject()) {
                    return (ObjectNode) node;
                }
            }
        } catch (IOException ignored) {
            // corrupt or unreadable file: start from an empty root
        }
        return mapper.createObjectNode();
    }

    public String aboutLanguage() {
        String lang = readRoot().path(KEY_ABOUT_LANGUAGE).asText("");
        return lang.isBlank() ? DEFAULT_LANGUAGE : lang;
    }

    public int fontSize() {
        int size = readRoot().path(KEY_FONT_SIZE).asInt(-1);
        return (size < AboutDialog.MIN_FONT_SIZE || size > AboutDialog.MAX_FONT_SIZE)
                ? DEFAULT_FONT_SIZE : size;
    }

    private void write(ObjectNode root) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Settings persistence must never crash the dialog; silently keep session values.
        }
    }

    public void setAboutLanguage(String lang) {
        ObjectNode root = readRoot();
        root.put(KEY_ABOUT_LANGUAGE, lang);
        write(root);
    }

    public void setFontSize(int size) {
        ObjectNode root = readRoot();
        root.put(KEY_FONT_SIZE, size);
        write(root);
    }
}
