package com.haarer.saf.mcpserver;

import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Modal editor for every key in {@link PluginConfig}: label, current value,
 * a help tooltip, and the property key.
 *
 * <p>Built from the catalog rather than hand-wired, so a new option becomes
 * editable by adding one line to {@link PluginConfig#options()}. Nothing is
 * written until Save; the values are re-read per turn, so a save takes effect
 * on the next message without restarting anything.
 */
public final class ConfigDialog extends JDialog {

    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final Map<String, JLabel> errors = new LinkedHashMap<>();
    private final Map<String, JLabel> names = new LinkedHashMap<>();
    private final File file;
    private static final java.awt.Color ERROR_TEXT = new java.awt.Color(190, 30, 30);
    private static final int FIELD_COLUMNS = 28;

    /** Tooltip text is HTML so a long help string wraps inside the tooltip. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private ConfigDialog(Window owner) {
        super(owner, "MCP Server Configuration", ModalityType.APPLICATION_MODAL);
        this.file = PluginConfig.file();
        Properties stored = PluginConfig.load();

        // One row per option. The help is a tooltip on the field; the
        // validation error line shares the field's cell, stacked under it, so
        // it cannot drift away: a separate block of error text is laid out on
        // its own row heights and lines up with nothing as soon as one field
        // is taller than the others.
        var form = new JPanel(new GridBagLayout());
        var c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        for (PluginConfig.Option option : PluginConfig.options()) {
            c.gridx = 0;
            c.gridy++;
            c.weightx = 0;
            c.weighty = 0;
            JLabel name = new JLabel(option.label());
            form.add(name, c);
            names.put(option.key(), name);

            var cell = new JPanel();
            cell.setOpaque(false);
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));

            JComponent input = field(option, stored);
            input.setAlignmentX(LEFT_ALIGNMENT);
            // The help text is a tooltip on the field rather than a line under
            // it: the field keeps the width its value needs, and each row is a
            // line shorter.
            input.setToolTipText("<html>" + escape(option.help()) + "</html>");
            cell.add(input);

            JLabel err = new JLabel(" ");
            err.setForeground(ERROR_TEXT);
            err.setFont(err.getFont().deriveFont(Font.PLAIN, 10f));
            err.setAlignmentX(LEFT_ALIGNMENT);
            errors.put(option.key(), err);
            cell.add(err);

            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            fields.put(option.key(), input);
            form.add(cell, c);
        }

        // BM25 is the only reader of the cap and the score, so with selection
        // off those rows are greyed out and react to the switch live.
        var bm25 = (JCheckBox) fields.get(PluginConfig.TOOL_BM25);
        bm25.addItemListener(e -> applySelectionDependencies());
        applySelectionDependencies();

        var body = new JPanel(new BorderLayout(0, 8));
        body.add(form, BorderLayout.CENTER);

        var save = new JButton("Save");
        save.addActionListener(e -> save());
        var cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(save);
        buttons.add(cancel);
        body.add(buttons, BorderLayout.SOUTH);

        // Where the values actually live, so the dialog says so.
        var path = new JLabel("Saved to " + file.getAbsolutePath());
        path.setFont(path.getFont().deriveFont(Font.PLAIN, 10f));
        path.setForeground(java.awt.Color.GRAY);
        path.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

        var scroll = new JScrollPane(body,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        var outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        outer.add(scroll, BorderLayout.CENTER);
        outer.add(path, BorderLayout.SOUTH);
        setContentPane(outer);

        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(Math.max(320, getWidth() - 40), 320));
        setLocationRelativeTo(owner);
    }


    /**
     * Grey out the options BM25 is the only reader of. The row's name label
     * fades with the field, so the whole row reads as inactive rather than
     * just the input.
     */
    private void applySelectionDependencies() {
        var bm25 = (JCheckBox) fields.get(PluginConfig.TOOL_BM25);
        boolean selectionOn = bm25 != null && bm25.isSelected();
        for (var option : PluginConfig.options()) {
            if (!PluginConfig.dependsOnToolSelection(option.key())) {
                continue;
            }
            fields.get(option.key()).setEnabled(selectionOn);
            var name = names.get(option.key());
            if (name != null) {
                name.setEnabled(selectionOn);
            }
        }
    }

    /** Open the dialog for {@code owner}; returns once it is closed. */
    public static void show(Component owner) {
        Window w = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        new ConfigDialog(w).setVisible(true);
    }

    private JComponent field(PluginConfig.Option option, Properties stored) {
        String value = PluginConfig.current(option, stored);
        if (option.type() == PluginConfig.Type.BOOL) {
            JCheckBox box = new JCheckBox("enabled", "true".equalsIgnoreCase(value));
            return box;
        }
        if (option.type() == PluginConfig.Type.MULTILINE) {
            JTextArea area = new JTextArea(value, 3, FIELD_COLUMNS);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            return new JScrollPane(area);
        }
        if (option.type() == PluginConfig.Type.SECRET) {
            return new JPasswordField(value, FIELD_COLUMNS);
        }
        return new JTextField(value, FIELD_COLUMNS);
    }

    /** The text currently in a field, unwrapping the scrolled text area. */
    private static String textOf(JComponent component) {
        if (component instanceof JCheckBox box) {
            return box.isSelected() ? "true" : "false";
        }
        if (component instanceof JScrollPane scroll
            && scroll.getViewport().getView() instanceof JTextArea area) {
            return area.getText();
        }
        if (component instanceof JPasswordField secret) {
            return new String(secret.getPassword());
        }
        if (component instanceof JTextField field) {
            return field.getText();
        }
        return "";
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<>();
        boolean ok = true;
        for (PluginConfig.Option option : PluginConfig.options()) {
            String raw = textOf(fields.get(option.key()));
            String error = PluginConfig.validate(option, raw);
            if (error == null) {
                ok = false;
                errors.get(option.key()).setText(error);
                continue;
            }
            errors.get(option.key()).setText(" ");
            values.put(option.key(), error.isEmpty() ? defaultFor(option) : error);
        }
        if (!ok) {
            JOptionPane.showMessageDialog(this,
                "Fix the highlighted values before saving.", "Invalid value",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PluginConfig.save(values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Could not write " + file + ":\n" + e.getMessage(), "Not saved",
                JOptionPane.ERROR_MESSAGE);
            return;
        }
        dispose();
    }

    /**
     * What to store for a field left empty: the option's own default, so the
     * file stays explicit about what is in effect.
     */
    private static String defaultFor(PluginConfig.Option option) {
        Object def = option.def();
        if (def == null) {
            return "";
        }
        if (option.type() == PluginConfig.Type.BOOL) {
            return Boolean.TRUE.equals(def) ? "true" : "false";
        }
        return String.valueOf(def);
    }
}
