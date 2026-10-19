package com.saberw.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Modal "About program" dialog (TZ 2026-10-19, step 3):
 * non-editable wrapped JTextArea in a JScrollPane,
 * language combo (ru, en, zh, fr, es, pt), font-size spinner 8-40 applied
 * immediately to the text area, «Бу» button copying the current text to the
 * system clipboard and «Закрыть». The chosen language and font size are
 * persisted in the settings-JSON (fields aboutLanguage, font.size) and
 * restored on opening. The owner's name is not mentioned anywhere.
 */
public final class AboutDialog extends JDialog {

    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 40;

    private final JTextArea textArea = new JTextArea();
    private final JComboBox<String> languageBox = new JComboBox<>(AboutTexts.LANGUAGES);
    private final JSpinner fontSpinner =
            new JSpinner(new SpinnerNumberModel(UiSettings.DEFAULT_FONT_SIZE, MIN_FONT_SIZE, MAX_FONT_SIZE, 1));
    private final UiSettings settings;

    /** Uses the default settings-JSON location (~/.saberw2/settings.json). */
    public AboutDialog(JFrame owner) {
        this(owner, new UiSettings(UiSettings.defaultSettingsFile()));
    }

    public AboutDialog(JFrame owner, UiSettings settings) {
        super(owner, "О программе", true);
        this.settings = settings;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(760, 520);
        if (owner != null) {
            setLocationRelativeTo(owner);
        } else {
            setLocationRelativeTo(null);
        }

        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(monospaced(currentFontSize()));
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(new JLabel("Язык: "));
        controls.add(languageBox);
        controls.add(Box.createHorizontalStrut(12));
        controls.add(new JLabel("Шрифт: "));
        fontSpinner.setPreferredSize(fontSpinner.getMinimumSize());
        controls.add(fontSpinner);
        controls.add(Box.createHorizontalGlue());
        JButton copyBtn = new JButton("Бу");
        copyBtn.setToolTipText("Копировать в буфер обмена");
        JButton closeBtn = new JButton("Закрыть");
        controls.add(copyBtn);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(closeBtn);
        content.add(controls, BorderLayout.SOUTH);
        setContentPane(content);

        // Restore persisted selection and size.
        String lang = settings.aboutLanguage();
        if (indexOfLanguage(lang) >= 0) {
            languageBox.setSelectedItem(lang);
        }
        fontSpinner.setValue(settings.fontSize());
        applyText();

        languageBox.addActionListener(e -> {
            settings.setAboutLanguage(selectedLanguage());
            applyText();
        });
        fontSpinner.addChangeListener(e -> {
            int size = (Integer) fontSpinner.getValue();
            textArea.setFont(monospaced(size));
            settings.setFontSize(size);
        });
        copyBtn.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(textArea.getText()), null));
        closeBtn.addActionListener(e -> dispose());
    }

    /** Deprecated frame-less form kept for backward compatibility. */
    @Deprecated
    public AboutDialog() {
        this((JFrame) null, new UiSettings(legacySettingsPath()));
    }

    private static java.nio.file.Path legacySettingsPath() {
        String dir = System.getProperty("java.io.tmpdir");
        return java.nio.file.Path.of(dir, "saberw-about-settings-compat.json");
    }

    private int indexOfLanguage(String lang) {
        for (int i = 0; i < AboutTexts.LANGUAGES.length; i++) {
            if (AboutTexts.LANGUAGES[i].equals(lang)) {
                return i;
            }
        }
        return -1;
    }

    private String selectedLanguage() {
        Object sel = languageBox.getSelectedItem();
        return sel == null ? UiSettings.DEFAULT_LANGUAGE : sel.toString();
    }

    private int currentFontSize() {
        Object v = fontSpinner.getValue();
        return v instanceof Integer i ? i : UiSettings.DEFAULT_FONT_SIZE;
    }

    private void applyText() {
        textArea.setText(AboutTexts.load(selectedLanguage()));
        textArea.setCaretPosition(0);
    }

    private static Font monospaced(int size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** Shows the dialog on the EDT relative to the given owner frame. */
    public static void show(JFrame owner) {
        SwingUtilities.invokeLater(() -> new AboutDialog(owner).setVisible(true));
    }
}
