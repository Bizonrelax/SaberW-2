package com.saberw.gui;

import com.saberw.core.CompressionSettings;
import com.saberw.core.Compressor;
import com.saberw.core.Decompressor;
import com.saberw.core.DictionaryIdGenerator;
import com.saberw.model.DictEntry;
import com.saberw.model.Dictionary;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Simple main window (MVP stage 14): two text areas and the buttons
 * Compress / Decompress / For bot / Clear. All heavy work runs in a SwingWorker,
 * the EDT is never blocked (see Technical Specification section 6).
 */
public final class MainWindow extends JFrame {

    private final JTextArea inputArea = new JTextArea();
    private final JTextArea outputArea = new JTextArea();
    private final JCheckBox firstMessageCheck = new JCheckBox("Первое сообщение", true);

    private Dictionary dictionary = new Dictionary();
    private String dictionaryId = new DictionaryIdGenerator().next(id -> true);

    public MainWindow() {
        super("SaberW-2");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationByPlatform(true);

        JPanel top = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new GridLayout(1, 5, 4, 0));
        JButton compressBtn = new JButton("Сжать");
        JButton decompressBtn = new JButton("Распаковать");
        JButton forBotBtn = new JButton("Для бота");
        JButton clearBtn = new JButton("Очистить");
        buttons.add(compressBtn);
        buttons.add(decompressBtn);
        buttons.add(forBotBtn);
        buttons.add(clearBtn);
        top.add(buttons, BorderLayout.NORTH);
        top.add(firstMessageCheck, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JScrollPane in = new JScrollPane(inputArea);
        JScrollPane out = new JScrollPane(outputArea);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, in, out);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        compressBtn.addActionListener(e -> runAsync(() -> {
            Compressor compressor = new Compressor(CompressionSettings.maxCjk());
            Compressor.Result result = compressor.compress(inputArea.getText());
            dictionary = result.dictionary();
            outputArea.setText(result.compressedText());
        }));

        decompressBtn.addActionListener(e -> runAsync(() -> {
            Decompressor decompressor = new Decompressor(dictionary);
            try {
                outputArea.setText(decompressor.decompress(inputArea.getText()));
            } catch (Decompressor.DecompressionException ex) {
                outputArea.setText("");
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Ошибка декомпрессии", JOptionPane.ERROR_MESSAGE);
            }
        }));

        forBotBtn.addActionListener(e -> {
            String export = buildBotExport();
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(export), null);
            markAllSentToBot();
        });

        clearBtn.addActionListener(e -> {
            inputArea.setText("");
            outputArea.setText("");
        });
    }

    /** Asynchronous execution: compression/decompression never blocks the EDT. */
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

    private String buildBotExport() {
        StringBuilder sb = new StringBuilder();
        if (firstMessageCheck.isSelected()) {
            sb.append(loadTemplate("templates/bot-block-base.ru.txt")).append("\n\n");
            sb.append(loadTemplate("templates/bot-block-normalization.ru.txt")).append("\n\n");
            appendDictLines(sb, dictionary.entries(), true);
        } else {
            List<DictEntry> newEntries = dictionary.entries().stream()
                    .filter(en -> !en.sentToBot()).toList();
            if (newEntries.isEmpty()) {
                sb.append("Сообщение:\n___\n");
            } else {
                sb.append(loadTemplate("templates/bot-instruction-minimal.ru.txt")).append("\n\n");
                sb.append("Дополнение словаря:\n");
                appendDictLines(sb, newEntries, false);
                sb.append("___\n");
            }
        }
        sb.append(outputArea.getText());
        return sb.toString();
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

    private void markAllSentToBot() {
        Dictionary updated = new Dictionary();
        for (DictEntry en : dictionary.entries()) {
            updated.put(en.withSentToBot(true));
        }
        dictionary = updated;
        firstMessageCheck.setSelected(false);
    }

    private static String loadTemplate(String resource) {
        try (InputStream is = MainWindow.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return "";
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
