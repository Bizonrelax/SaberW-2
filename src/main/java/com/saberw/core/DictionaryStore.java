package com.saberw.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Working-folder persistence for dictionaries (see Technical Specification "ИНСТРУКЦИЯ ДЛЯ ГВЕН", item 3).
 * A dictionary is saved as {@code <id>.saberw.json} in the working directory.
 * Uniqueness of a 6-char ID means: no other file with that ID exists in the folder
 * (or the file belongs to the very same session / is empty).
 */
public final class DictionaryStore {

    public static final String FILE_SUFFIX = ".saberw.json";

    private final Path workDir;

    public DictionaryStore(Path workDir) {
        this.workDir = workDir;
    }

    public static DictionaryStore inWorkingDirectory() {
        return new DictionaryStore(Path.of(System.getProperty("user.dir")));
    }

    public Path fileFor(String id) {
        return workDir.resolve(id + FILE_SUFFIX);
    }

    /** True if no saved dictionary with this ID occupies the working folder. */
    public boolean isIdAvailable(String id) {
        return !Files.exists(fileFor(id));
    }

    public List<String> listIds() {
        try (Stream<Path> stream = Files.list(workDir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(FILE_SUFFIX))
                    .map(n -> n.substring(0, n.length() - FILE_SUFFIX.length()))
                    .filter(n -> n.matches("[A-Za-z0-9]{6}"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            return List.of();
        }
    }

    public void save(DictionaryJsonSerializer.Root root) throws IOException {
        new DictionaryJsonSerializer().save(fileFor(root.id()), root);
    }

    public DictionaryJsonSerializer.Root load(String id) throws IOException {
        return new DictionaryJsonSerializer().load(fileFor(id));
    }

    /** Loads a dictionary from an arbitrary path (e.g. an interrupted-* recovery file). */
    public DictionaryJsonSerializer.Root loadFile(Path file) throws IOException {
        return new DictionaryJsonSerializer().load(file);
    }

    public boolean exists(String id) {
        return Files.exists(fileFor(id));
    }

    /** Writes a recovery file {@code interrupted-<id>.saberw.json}, returns its path. */
    public Path saveInterrupted(DictionaryJsonSerializer.Root root) throws IOException {
        Path target = workDir.resolve(("interrupted-" + root.id()).toLowerCase(Locale.ROOT) + FILE_SUFFIX);
        Files.writeString(target, new DictionaryJsonSerializer().toJson(root), StandardCharsets.UTF_8);
        return target;
    }
}
