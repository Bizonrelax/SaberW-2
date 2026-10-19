package com.saberw.gui;

import com.saberw.core.BotExporter;
import com.saberw.core.CompressionProgress;
import com.saberw.core.CompressionSettings;
import com.saberw.core.Compressor;
import com.saberw.core.Decompressor;
import com.saberw.core.DictionaryIdGenerator;
import com.saberw.core.DictionaryJsonSerializer;
import com.saberw.core.DictionaryStore;
import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;
import com.saberw.model.DictionaryMetadata;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Main window (MVP): two text areas, dictionary viewer, Compress / Decompress /
 * For bot / Save / Load / Clear buttons, compression-mode selector, stop button,
 * dynamic progress bar and Esc interruption (Technical Specification section 6).
 * All heavy work runs in a SwingWorker — the EDT is never blocked.
 */
public final class MainWindow extends JFrame {

    private final JTextArea inputArea = new JTextArea();
    private final JTextArea outputArea = new JTextArea();
    private final JTextArea dictArea = new JTextArea();
    private final JCheckBox firstMessageCheck = new JCheckBox("Первое сообщение", true);
    private final JCheckBox ignoreCommentsCheck = new JCheckBox("Игнорировать комментарии", false);
    private final JComboBox<String> modeBox = new JComboBox<>(new String[]{"max-cjk", "code", "chat"});
    private final JButton compressBtn = new JButton("Сжать");
    private final JButton decompressBtn = new JButton("Распаковать");
    private final JButton forBotBtn = new JButton("Для бота");
    private final JButton saveBtn = new JButton("Сохранить");
    private final JButton loadBtn = new JButton("Загрузить");
    private final JButton clearBtn = new JButton("Очистить");
    private final JButton stopBtn = new JButton("Остановить поиск");
    private final JButton aboutBtn = new JButton("Опр");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    /** Cancellation flag: set on the EDT, polled by the background thread at iteration boundaries. */
    private volatile boolean cancelled;

    private Dictionary dictionary = new Dictionary();
    private String dictionaryId = null;
    private DictionaryMetadata metadata = new DictionaryMetadata();
    private final DictionaryStore store = DictionaryStore.inWorkingDirectory();
    private CompressionSettings settings = CompressionSettings.maxCjk();

    public MainWindow() {
        super("SaberW-2");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 760);
        setLocationByPlatform(true);

        JPanel buttons = new JPanel(new GridLayout(1, 8, 4, 0));
        buttons.add(compressBtn);
        buttons.add(decompressBtn);
        buttons.add(forBotBtn);
        buttons.add(saveBtn);
        buttons.add(loadBtn);
        buttons.add(clearBtn);
        buttons.add(stopBtn);
        aboutBtn.setToolTipText("О программе");
        buttons.add(aboutBtn);
        JPanel options = new JPanel(new BorderLayout());
        options.add(new JLabel("Режим: "), BorderLayout.WEST);
        options.add(modeBox, BorderLayout.CENTER);
        options.add(ignoreCommentsCheck, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout());
        north.add(buttons, BorderLayout.NORTH);
        north.add(options, BorderLayout.CENTER);
        north.add(firstMessageCheck, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        JScrollPane in = new JScrollPane(inputArea);
        JScrollPane out = new JScrollPane(outputArea);
        JScrollPane dict = new JScrollPane(dictArea);
        dictArea.setEditable(false);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, in, out);
        split.setResizeWeight(0.5);
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, split, dict);
        vertical.setResizeWeight(0.8);
        add(vertical, BorderLayout.CENTER);

        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.SOUTH);

        stopBtn.setEnabled(false);
        getRootPane().registerKeyboardAction(e -> stopSearch(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        compressBtn.addActionListener(e -> onCompress());
        decompressBtn.addActionListener(e -> runAsync(() -> {
            Decompressor decompressor = new Decompressor(dictionary);
            try {
                outputArea.setText(decompressor.decompress(inputArea.getText()));
                metadata = metadata.withDecompression();
            } catch (Decompressor.DecompressionException ex) {
                outputArea.setText("");
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        "Ошибка декомпрессии", JOptionPane.ERROR_MESSAGE);
            }
        }));
        forBotBtn.addActionListener(e -> {
            String export = BotExporter.build(dictionary, firstMessageCheck.isSelected(),
                    settings.normalizeCase(), outputArea.getText());
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(export), null);
            dictionary = BotExporter.markAllSent(dictionary);
            metadata = metadata.withBotExport();
            ensureId();
            try {
                store.save(new DictionaryJsonSerializer.Root(dictionaryId, Instant.now(), dictionary, settings, metadata));
            } catch (Exception io) {
                JOptionPane.showMessageDialog(this, io.getMessage(), "Ошибка сохранения", JOptionPane.ERROR_MESSAGE);
                return;
            }
            firstMessageCheck.setSelected(false);
            refreshDictView();
        });
        saveBtn.addActionListener(e -> saveDictionary());
        loadBtn.addActionListener(e -> loadDictionaryDialog());
        aboutBtn.addActionListener(e -> new AboutDialog(this).setVisible(true));
        clearBtn.addActionListener(e -> {
            inputArea.setText("");
            outputArea.setText("");
        });
        modeBox.addActionListener(e -> settings = parseMode());
        // TZ 18.8: auto checkbox from metadata counters
        if (metadata.totalCompressions() == 0 && metadata.supplementCount() == 0) {
            firstMessageCheck.setSelected(true);
        } else {
            firstMessageCheck.setSelected(false);
        }
    }

    private void onCompress() {
        settings = parseMode();
        cancelled = false;
        stopBtn.setEnabled(true);
        final CompressionSettings localSettings = settings;
        final Dictionary baseline = dictionary;
        final String sourceText = inputArea.getText();
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            private Compressor.Result result;
            private boolean interrupted;
            private Compressor.CompressionInterruptedException iex;

            @Override
            protected Void doInBackground() {
                Compressor compressor = new Compressor(localSettings);
                CompressionProgress progress = new CompressionProgress() {
                    private long lastPublish = 0;

                    @Override
                    public boolean isCancelled() {
                        return cancelled;
                    }

                    @Override
                    public void update(String phase, int percentDone) {
                        long now = System.currentTimeMillis();
                        if (now - lastPublish > 100) {
                            lastPublish = now;
                            publish(Math.max(0, Math.min(100, percentDone)));
                        }
                    }
                };
                try {
                    result = compressor.compress(sourceText, baseline, progress);
                    dictionary = result.dictionary();
                    metadata = metadata.withCompression(java.time.LocalDate.now().toString());
                } catch (Compressor.CompressionInterruptedException ex) {
                    interrupted = true;
                    iex = ex;
                    dictionary = ex.partialResult().dictionary();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                progressBar.setValue(chunks.get(chunks.size() - 1));
                if (!progressBar.isVisible()) {
                    progressBar.setVisible(true);
                }
            }

            @Override
            protected void done() {
                stopBtn.setEnabled(false);
                progressBar.setVisible(false);
                try {
                    get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(MainWindow.this,
                            cause.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
                if (interrupted && iex != null) {
                    try {
                        ensureId();
                        Path p = store.saveInterrupted(new DictionaryJsonSerializer.Root(
                                dictionaryId, Instant.now(), dictionary));
                        outputArea.setText(iex.partialResult().compressedText());
                        refreshDictView();
                        JOptionPane.showMessageDialog(MainWindow.this,
                                "Сжатие остановлено. Промежуточный словарь сохранён:\n" + p,
                                "Прервано", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception io) {
                        JOptionPane.showMessageDialog(MainWindow.this, io.getMessage(),
                                "Ошибка сохранения", JOptionPane.ERROR_MESSAGE);
                    }
                    return;
                }
                if (result != null) {
                    outputArea.setText(result.compressedText());
                    refreshDictView();
                }
            }
        };
        worker.execute();
    }

    private void stopSearch() {
        cancelled = true;
    }

    private CompressionSettings parseMode() {
        CompressionSettings base = switch ((String) modeBox.getSelectedItem()) {
            case "code" -> CompressionSettings.code();
            case "chat" -> CompressionSettings.chat();
            default -> CompressionSettings.maxCjk();
        };
        return base.withIgnoreComments(ignoreCommentsCheck.isSelected());
    }

    private void ensureId() {
        if (dictionaryId == null) {
            dictionaryId = new DictionaryIdGenerator().next(store::isIdAvailable);
        }
    }

    private void saveDictionary() {
        if (dictionary.size() == 0) {
            JOptionPane.showMessageDialog(this, "Словарь пуст", "Сохранение", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ensureId();
        try {
            store.save(new DictionaryJsonSerializer.Root(dictionaryId, Instant.now(), dictionary, settings, metadata));
            JOptionPane.showMessageDialog(this,
                    "Сохранено: " + store.fileFor(dictionaryId).toAbsolutePath(),
                    "Сохранение", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Ошибка сохранения",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadDictionaryDialog() {
        java.util.List<String> ids = store.listIds();
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "В рабочей папке нет файлов *.saberw.json", "Загрузка", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String chosen = (String) JOptionPane.showInputDialog(this, "Словарь:", "Загрузка",
                JOptionPane.PLAIN_MESSAGE, null, ids.toArray(), ids.get(0));
        if (chosen == null) {
            return;
        }
        try {
            DictionaryJsonSerializer.Root root = store.load(chosen);
            dictionary = root.dictionary();
            dictionaryId = root.id();
            metadata = root.metadata();
            // TZ 18.8: auto checkbox from metadata counters
            if (metadata.totalCompressions() == 0 && metadata.supplementCount() == 0) {
                firstMessageCheck.setSelected(true);
            } else {
                firstMessageCheck.setSelected(false);
            }
            refreshDictView();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Ошибка загрузки",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshDictView() {
        StringBuilder sb = new StringBuilder();
        for (DictEntry en : dictionary.entries()) {
            sb.append(en.code()).append('\t')
              .append(en.value().tokens().get(0).text())
              .append("\tF=").append(en.frequency())
              .append(en.sentToBot() ? "\t[bot]" : "")
              .append('\n');
        }
        dictArea.setText(sb.toString());
    }

    /** Simple async task wrapper for non-compression actions. */
    private void runAsync(Runnable task) {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                task.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(MainWindow.this,
                            cause.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
