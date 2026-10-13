package com.saberw.cli;

import com.saberw.core.CompressionSettings;
import com.saberw.core.Compressor;
import com.saberw.core.Decompressor;
import com.saberw.core.DictionaryIdGenerator;
import com.saberw.core.DictionaryJsonSerializer;
import com.saberw.core.DictionaryStore;
import com.saberw.model.Dictionary;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Headless command-line entry point. Provides a fully runnable version of the program
 * without a display (build/CI/smoke testing):
 *   saberw compress &lt;file&gt; [-mode max-cjk|code|chat] [-dict &lt;file.saberw.json&gt;] [-out &lt;file.saberw.json&gt;] [-text-out &lt;file&gt;] [-bot-out &lt;file&gt;]
 *   saberw decompress &lt;file.saberw.json&gt; &lt;compressed-file&gt;
 *   saberw selftest
 */
public final class SaberCli {

    private SaberCli() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length == 0) {
            usage(out);
            System.exit(2);
        }
        switch (args[0]) {
            case "compress" -> compress(out, args);
            case "decompress" -> decompress(out, args);
            case "selftest" -> selftest(out);
            default -> {
                usage(out);
                System.exit(2);
            }
        }
    }

    private static void usage(PrintStream out) {
        out.println("SaberW-2 CLI");
        out.println("  saberw compress <file> [-mode max-cjk|code|chat] [-dict <in.saberw.json>] [-out <out.saberw.json>] [-text-out <compressed.txt>] [-bot-out <for-bot.txt>]");
        out.println("  saberw decompress <dict.saberw.json> <compressed-file>");
        out.println("  saberw selftest");
    }

    private static void compress(PrintStream out, String[] args) throws Exception {
        if (args.length < 2) {
            usage(out);
            System.exit(2);
        }
        Path input = Path.of(args[1]);
        CompressionSettings settings = CompressionSettings.maxCjk();
        Path dictOut = null;
        Path dictIn = null;
        Path textOut = null;
        Path botOut = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-mode" -> settings = parseMode(args[++i]);
                case "-out" -> dictOut = Path.of(args[++i]);
                case "-dict" -> dictIn = Path.of(args[++i]);
                case "-text-out" -> textOut = Path.of(args[++i]);
                case "-bot-out" -> botOut = Path.of(args[++i]);
                default -> {
                    usage(out);
                    System.exit(2);
                }
            }
        }

        // Session continuation: reuse an existing dictionary file as baseline
        Dictionary baseline = new Dictionary();
        String id;
        if (dictIn != null) {
            DictionaryJsonSerializer.Root base = new DictionaryJsonSerializer().load(dictIn);
            baseline = base.dictionary();
            id = base.id();
        } else {
            DictionaryStore store = DictionaryStore.inWorkingDirectory();
            id = new DictionaryIdGenerator().next(store::isIdAvailable);
        }

        String text = Files.readString(input, StandardCharsets.UTF_8);
        Compressor compressor = new Compressor(settings);
        Compressor.Result result = compressor.compress(text, baseline, com.saberw.core.CompressionProgress.NONE);

        DictionaryJsonSerializer.Root root =
                new DictionaryJsonSerializer.Root(id, Instant.now(), result.dictionary());
        Path saved = dictOut != null ? dictOut : Path.of(id + DictionaryStore.FILE_SUFFIX);
        new DictionaryJsonSerializer().save(saved, root);
        if (textOut != null) {
            Files.writeString(textOut, result.compressedText(), StandardCharsets.UTF_8);
        }
        if (botOut != null) {
            boolean firstMessage = dictIn == null;
            Files.writeString(botOut, com.saberw.core.BotExporter.build(
                    result.dictionary(), firstMessage, settings.normalizeCase(),
                    result.compressedText()), StandardCharsets.UTF_8);
        }

        long originalChars = text.codePointCount(0, text.length());
        long compressedChars = result.compressedText().codePointCount(0, result.compressedText().length());
        double ratio = originalChars == 0 ? 1.0 : (double) compressedChars / originalChars;

        out.println("OK compressed: entries=" + result.dictionary().size()
                + " originalChars=" + originalChars
                + " compressedChars=" + compressedChars
                + " ratio=" + String.format(java.util.Locale.ROOT, "%.3f", ratio));
        out.println("Dictionary saved: " + saved.toAbsolutePath());
    }

    private static void decompress(PrintStream out, String[] args) throws Exception {
        if (args.length < 3) {
            usage(out);
            System.exit(2);
        }
        DictionaryJsonSerializer.Root root = new DictionaryJsonSerializer().load(Path.of(args[1]));
        String compressed = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);
        Decompressor decompressor = new Decompressor(root.dictionary());
        out.println("OK decompressed chars=" + decompressor.decompress(compressed).length());
    }

    private static CompressionSettings parseMode(String mode) {
        return switch (mode) {
            case "max-cjk" -> CompressionSettings.maxCjk();
            case "code" -> CompressionSettings.code();
            case "chat" -> CompressionSettings.chat();
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    /** Round-trip invariant check on a built-in corpus; exits non-zero on failure. */
    private static void selftest(PrintStream out) {
        String[] corpus = {
            "Программа сжимает текст. Программа сжимает текст эффективно, программа распаковывает текст обратно.",
            "compression compression compression decompression decompression dictionary dictionary dictionary",
            "привет 👨‍👩‍👧‍ привет 😀😀😀 тест тест тест эмодзи 🇷🇺 эмодзи",
            "раздел § раздел § ещё раздел §§§ конец конец конец",
            "код 㐀 символ 㐁 код 㐀 символ 㐁 повтор повтор повтор",
            "подозрительная последовательность §#㐄 внутри текста §#㐄 §#㐄",
            "",
        };
        int failures = 0;
        for (String text : corpus) {
            Compressor c = new Compressor(CompressionSettings.maxCjk());
            Compressor.Result r = c.compress(text);
            Decompressor d = new Decompressor(r.dictionary());
            String back = d.decompress(r.compressedText());
            if (!back.equals(text.toLowerCase(java.util.Locale.ROOT))) {
                failures++;
                out.println("FAIL: " + text);
            }
        }
        // Dictionary must not make texts longer: verify strict improvement where candidates exist
        Compressor c = new Compressor(CompressionSettings.maxCjk());
        Compressor.Result r = c.compress(corpus[0]);
        long orig = corpus[0].toLowerCase(java.util.Locale.ROOT).codePointCount(0, corpus[0].length());
        long comp = r.compressedText().codePointCount(0, r.compressedText().length());
        if (comp >= orig || r.dictionary().size() == 0) {
            failures++;
            out.println("FAIL: no real compression (orig=" + orig + " compressed=" + comp
                    + " entries=" + r.dictionary().size() + ")");
        }
        // JSON round-trip through the serializer
        try {
            DictionaryJsonSerializer ser = new DictionaryJsonSerializer();
            DictionaryJsonSerializer.Root root = new DictionaryJsonSerializer.Root(
                    "ABC123", Instant.now(), r.dictionary());
            String json = ser.toJson(root);
            DictionaryJsonSerializer.Root loaded = ser.fromJson(json);
            Dictionary dict = loaded.dictionary();
            if (dict.size() != r.dictionary().size()) {
                failures++;
                out.println("FAIL: JSON round-trip lost entries");
            }
            String back = new Decompressor(dict).decompress(r.compressedText());
            if (!back.equals(corpus[0].toLowerCase(java.util.Locale.ROOT))) {
                failures++;
                out.println("FAIL: JSON-loaded dictionary broke round-trip");
            }
        } catch (Exception ex) {
            failures++;
            out.println("FAIL: JSON error: " + ex.getMessage());
        }
        if (failures == 0) {
            out.println("SELFTEST PASSED");
        } else {
            out.println("SELFTEST FAILED: " + failures);
            System.exit(1);
        }
    }
}
