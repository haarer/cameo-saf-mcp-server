package com.haarer.saf.mcpserver;

import com.jidesoft.docking.DockContext;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.ui.ProjectWindow;
import com.haarer.saf.mcpserver.protocol.McpSession;
import com.haarer.saf.mcpserver.protocol.McpToolDefinition;
import com.nomagic.magicdraw.ui.ProjectWindowsConfigurator;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Font;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.nomagic.magicdraw.core.Application;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import java.awt.Window;

/**
 * A dockable window inside the Cameo main frame showing MCP server status
 * plus a small LLM console.
 *
 * <p>Registered once via {@link ProjectWindowsManager.ConfiguratorRegistry};
 * MagicDraw calls {@link #configure} when a project is opened and the window
 * docks at the bottom of the main frame.
 *
 * <p>Layout (top to bottom):
 * <ol>
 *   <li>one status line: running/stopped, endpoint URL, tool count, active
 *       session count, total tool call count, LLM console context size
 *       (messages/chars) and cumulative token usage;</li>
 *   <li>a scrollable log: user turns are echoed, LLM replies stream in below
 *       in a distinct color, errors are red;</li>
 *   <li>a text entry that sends the typed line to the OpenAI-compatible LLM
 *       endpoint (see {@link LlmChatClient}) — turns entered while a reply is
 *       still streaming are queued — and a Clear button that resets the log
 *       and the conversation.</li>
 * </ol>
 */
public class McpStatusWindow implements ProjectWindowsConfigurator {

    public static final String WINDOW_ID = "com.haarer.saf.mcpserver.status";
    public static final String WINDOW_NAME = "MCP Server Status";

    private final Supplier<CameoMcpServer> serverSupplier;
    /**
     * The conversation, shared by every window for one plugin load. Supplied
     * rather than created here because MagicDraw recreates the project window
     * on every model load and save: a client owned by the window would restart
     * the conversation every time, which is what used to happen.
     */
    private final Supplier<LlmChatClient> llmSupplier;

    /** Project + manager captured in {@link #configure}, used to re-show the
     *  docked window from the Window menu after the user has closed it. Both
     *  are null until a project window is created. */
    private static volatile Project dockedProject;
    private static volatile ProjectWindowsManager dockedManager;

    private static JDialog detached;
    /**
     * The content of {@link #detached}. Kept so the fallback dialog's refresh
     * timer can be stopped when it is discarded: disposing a Swing dialog does
     * not stop a timer the content started itself, and that timer would then
     * wake once a second forever.
     */
    private static StatusContent detachedContent;

    /**
     * The content of the window most recently docked by {@link #configure}.
     * Held only so its refresh timer can be stopped when that window is
     * replaced: {@code removeWindow} disposes of the window by id, and the
     * content it owned is not reachable afterwards, so an orphaned timer
     * would otherwise wake once a second for the rest of the session.
     */
    private static StatusContent dockedContent;

    public McpStatusWindow(Supplier<CameoMcpServer> serverSupplier,
                            Supplier<LlmChatClient> llmSupplier) {
        this.serverSupplier = serverSupplier;
        this.llmSupplier = llmSupplier;
    }

    @Override
    public void configure(Project project, ProjectWindowsManager manager) {
        // Logged on entry and on failure: "registered" alone cannot tell a
        // configurator that was never invoked from one that threw.
        String projectName;
        try {
            projectName = project.getName();
        } catch (Throwable t) {
            projectName = "<unknown project>";
        }
        System.err.println("[CameoMcpServer] configure() called for project "
            + projectName + " - docking " + WINDOW_NAME);
        var info = new WindowComponentInfo(WINDOW_ID, WINDOW_NAME, null,
            DockContext.DOCK_SIDE_SOUTH, 0, true);
        info.setTabTitle("MCP Status");
        info.setState(DockContext.STATE_FRAMEDOCKED);
        info.setDockable(true);
        info.setFloatable(true);
        info.setAutohidable(true);
        info.setRearrangable(true);
        info.setMaximizable(true);
        info.setRemoveOnHide(false);
        // This configurator is invoked again whenever a project window is
        // created or rebuilt - which is what happens on a model load, a model
        // save and a switch between models. Adding unconditionally each time
        // is what left a second copy of the window behind: whether the
        // previous one was torn down first is the product's business, and
        // assuming either way is how the duplicate appeared. Removing the
        // window we own before adding is correct either way - a no-op when
        // the product already cleaned up, a replacement when it did not.
        try {
            manager.removeWindow(project, WINDOW_ID);
        } catch (Throwable t) {
            // No window of ours is registered yet, which is the normal case on
            // the first call. Not worth reporting and not worth failing over.
            System.err.println("[CameoMcpServer] no existing " + WINDOW_ID
                + " window to replace: " + t);
        }
        var content = new StatusContent(serverSupplier, llmSupplier);
        try {
            manager.addWindow(project, new ProjectWindow(info, content));
            // The window just replaced is gone either way - removed above, or
            // torn down by the product - so its timer has nothing left to
            // refresh, and it holds its content alive for the whole session.
            var replaced = dockedContent;
            dockedContent = content;
            if (replaced != null && replaced != content) {
                replaced.stop();
            }
        } catch (Throwable t) {
            System.err.println("[CameoMcpServer] ERROR: addWindow failed: " + t);
            t.printStackTrace(System.err);
            throw t;
        }
        dockedProject = project;
        dockedManager = manager;
        // The fallback dialog exists only for the case where no docked window
        // has been created yet. Now that one has, that dialog is a second view
        // of the same console - and with the conversation shared between
        // windows, two copies of it look like a bug rather than like the
        // fallback doing its job. Discarded only after addWindow succeeded, so
        // a failure here leaves the user with the window they already had.
        discardDetached();
    }

    /**
     * Dispose the fallback dialog if one is open, and stop its refresh timer.
     * Safe to call when there is none.
     */
    private static synchronized void discardDetached() {
        var dialog = detached;
        var content = detachedContent;
        detached = null;
        detachedContent = null;
        if (content != null) {
            content.stop();
        }
        if (dialog != null && dialog.isDisplayable()) {
            dialog.dispose();
        }
    }

    /**
     * Bring the MCP status window to the foreground from the Window menu.
     *
     * <p>Re-activates the docked window when one has been configured —
     * including after the user closes it, since the component is hidden
     * rather than removed. Only when no docked window exists (a project
     * that was already open at startup, so {@link #configure} was never
     * called) does it fall back to a standalone dialog.
     */
    public static synchronized void showOrActivate(Supplier<CameoMcpServer> serverSupplier,
                                                 Supplier<LlmChatClient> llmSupplier) {
        var mgr = dockedManager;
        var proj = dockedProject;
        if (mgr != null && proj != null) {
            try {
                mgr.activateWindow(proj, WINDOW_ID);
                return;
            } catch (Throwable t) {
                System.err.println("[CameoMcpServer] activateWindow failed, "
                    + "falling back to detached: " + t);
            }
        }
        showDetached(serverSupplier, llmSupplier);
    }

    /**
     * Show the status UI in a standalone dialog.
     *
     * <p>The docked path above only runs when Cameo creates a project window
     * <em>after</em> this configurator is registered. When a project is
     * already open at plugin startup — a restored session, or an install
     * where the plugin loads late — {@link #configure} is never called and
     * the docked window silently never appears. This gives the user a way in
     * that does not depend on that ordering.
     */
    public static synchronized void showDetached(Supplier<CameoMcpServer> serverSupplier,
                                                 Supplier<LlmChatClient> llmSupplier) {
        if (detached != null && detached.isDisplayable()) {
            detached.toFront();
            detached.requestFocus();
            return;
        }
        Window owner = Application.getInstance().getMainFrame();
        JDialog dialog = new JDialog(owner, WINDOW_NAME,
            java.awt.Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        var content = new StatusContent(serverSupplier, llmSupplier);
        // Retained so the dialog's refresh timer can be stopped if a docked
        // window later supersedes it; see discardDetached().
        detachedContent = content;
        dialog.setContentPane(content.panel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(500, 240));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        detached = dialog;
    }

    /**
     * Add a "MCP Server Status" item to the Window menu, creating that menu if
     * the product does not have one.
     *
     * @return true if the item is installed, false if the menu bar is not
     *         available yet (Cameo builds it during startup, after plugins
     *         initialise) and the caller should retry later
     */
    public static boolean installMenuItem(Supplier<CameoMcpServer> serverSupplier,
                                         Supplier<LlmChatClient> llmSupplier) {
        JMenuBar bar;
        try {
            bar = Application.getInstance().getMainFrame().getMainMenuBar();
        } catch (Exception | Error e) {
            return false;
        }
        if (bar == null || bar.getMenuCount() == 0) {
            return false;
        }
        JMenu target = findMenu(bar, "Window");
        if (target == null) {
            target = new JMenu("Window");
            bar.add(target);
        }
        JMenuItem item = new JMenuItem(WINDOW_NAME);
        item.addActionListener(e -> showOrActivate(serverSupplier, llmSupplier));
        target.addSeparator();
        target.add(item);
        bar.revalidate();
        return true;
    }

    /** Match a top-level menu by text, ignoring accelerators and ellipses. */
    private static JMenu findMenu(JMenuBar bar, String name) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu == null) {
                continue;
            }
            String text = menu.getText();
            if (text == null) {
                continue;
            }
            String plain = text.replace("&", "").replace("...", "").trim();
            if (plain.equalsIgnoreCase(name)) {
                return menu;
            }
        }
        return null;
    }

    /**
     * Install the Window menu item on the EDT without blocking startup.
     *
     * <p>Cameo builds its menu bar during startup, on the EDT, after plugins
     * initialise. A blocking {@code Thread.sleep} retry loop would starve the
     * very thread that creates the bar: it is null for the whole poll window,
     * then we give up just before it appears. A Swing timer polls
     * non-blockingly and installs the item on the first tick after the bar
     * exists, then stops.
     *
     * @param onInstalled called once, when the item is added
     * @param onGiveUp    called once, if the bar is still absent after the
     *                    bounded retry window
     */
    public static void scheduleMenuItemInstall(
            Supplier<CameoMcpServer> serverSupplier,
            Supplier<LlmChatClient> llmSupplier,
            Runnable onInstalled,
            Runnable onGiveUp) {
        final int maxAttempts = 120; // ~60 s at 500 ms
        SwingUtilities.invokeLater(() -> {
            int[] attempts = {0};
            Timer[] holder = new Timer[1];
            holder[0] = new Timer(500, e -> {
                attempts[0]++;
                if (installMenuItem(serverSupplier, llmSupplier)) {
                    holder[0].stop();
                    onInstalled.run();
                } else if (attempts[0] >= maxAttempts) {
                    holder[0].stop();
                    onGiveUp.run();
                }
            });
            holder[0].setRepeats(true);
            holder[0].start();
        });
    }

    private static final class StatusContent implements WindowComponentContent {
        private static final Color OK_COLOR = new Color(0, 128, 0);
        private static final Color STOP_COLOR = Color.RED;
        private static final Color USER_COLOR = new Color(70, 70, 70);
        private static final Color REPLY_COLOR = new Color(25, 110, 200);
        private static final Color ERROR_COLOR = new Color(190, 30, 30);
        private static final Color TOOL_COLOR = new Color(180, 100, 0);
        private static final Color INFO_COLOR = Color.GRAY;
        // Deliberately desaturated and set in italics: reasoning is context for
        // the reply, and must not be mistaken for the answer itself.
        private static final Color THINKING_COLOR = new Color(115, 115, 140);
        private final Supplier<CameoMcpServer> serverSupplier;
        private final LlmChatClient llm;
        private final JPanel panel;
        private final JLabel statusLine;
        private final JLabel detailLine;
        private final JTextPane logPane;
        private final JTextField input;
        private final Timer timer;
        private List<String> lastMcpToolNames = new ArrayList<>();

        StatusContent(Supplier<CameoMcpServer> serverSupplier,
                      Supplier<LlmChatClient> llmSupplier) {
            this.serverSupplier = serverSupplier;
            // The shared client, not a new one. Creating it here bound the
            // conversation to this window's lifetime, and MagicDraw rebuilds
            // the window on every model load and save - so the model could cut
            // its own conversation off by saving the model.
            this.llm = llmSupplier.get();

            // -- status row: one line ------------------------------------
            statusLine = new JLabel("MCP Server: ...");
            statusLine.setFont(statusLine.getFont().deriveFont(Font.BOLD, 11f));
            detailLine = new JLabel(" ");
            detailLine.setFont(detailLine.getFont().deriveFont(Font.PLAIN, 11f));
            var statusRow = new JPanel(new BorderLayout(10, 0));
            statusRow.setOpaque(false);
            statusRow.add(statusLine, BorderLayout.WEST);
            statusRow.add(detailLine, BorderLayout.CENTER);

            // -- log area with scroll bar --------------------------------
            logPane = new JTextPane();
            logPane.setEditable(false);
            logPane.setBackground(Color.WHITE);
            logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            var logScroll = new JScrollPane(logPane,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            // -- text entry + menu + clear -------------------------------
            input = new JTextField();
            input.addActionListener(e -> submit());
            var menu = new JButton("≡");
            menu.setToolTipText("Configuration");
            menu.setMargin(new Insets(0, 8, 0, 8));
            menu.setFocusPainted(false);
            menu.addActionListener(e -> ConfigDialog.show(menu));
            var clear = new JButton("Clear");
            clear.addActionListener(e -> clearAll());
            var inputEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            inputEast.setOpaque(false);
            inputEast.add(menu);
            inputEast.add(clear);
            var inputRow = new JPanel(new BorderLayout(6, 0));
            inputRow.setOpaque(false);
            inputRow.add(input, BorderLayout.CENTER);
            inputRow.add(inputEast, BorderLayout.EAST);

            panel = new JPanel(new BorderLayout(0, 0));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            panel.add(statusRow, BorderLayout.NORTH);
            panel.add(logScroll, BorderLayout.CENTER);
            panel.add(inputRow, BorderLayout.SOUTH);
            panel.setMinimumSize(new Dimension(500, 220));

            appendLine("LLM endpoint: " + llm.baseUrl(), INFO_COLOR);
            appendLine("Type a message and press Enter to chat with the LLM.", INFO_COLOR);
            // The conversation outlives this window: the client is shared for
            // the whole session, so a window built after a model load, a save
            // or a switch starts blank while the model still remembers
            // everything. Repopulating keeps the two from disagreeing.
            restoreConversation();

            refresh();
            timer = new Timer(1000, e -> refresh());
            timer.start();
        }

        /**
         * Print the conversation the client already holds into a freshly built
         * window, so a window that appears after a model load, a save or a
         * switch shows what the model can see rather than starting blank. A
         * no-op for a genuinely new conversation.
         */
        private void restoreConversation() {
            for (var e : llm.conversation()) {
                if ("user".equals(e.role())) {
                    appendLine("> " + e.text(), USER_COLOR);
                } else {
                    appendLine(e.text(), REPLY_COLOR);
                }
            }
        }

        /**
         * Stop the refresh timer. A Swing dialog being disposed does not stop
         * a timer its content started, and a content that is discarded would
         * otherwise keep waking once a second, holding itself and its window
         * alive for the rest of the session.
         */
        void stop() {
            timer.stop();
        }

        private void refresh() {
            syncMcpTools();
            String mcp;
            var server = serverSupplier.get();
            if (server == null) {
                statusLine.setText("MCP Server: not started");
                statusLine.setForeground(STOP_COLOR);
                mcp = " ";
            } else {
                boolean running = server.isRunning();
                statusLine.setText("MCP Server: " + (running ? "running" : "stopped"));
                statusLine.setForeground(running ? OK_COLOR : STOP_COLOR);
                mcp = running
                    ? "http://" + server.getHost() + ":" + server.getPort() + "/mcp"
                      + "   " + server.getToolCount() + " tools"
                      + "   " + server.getActiveSessions() + " sessions"
                      + "   " + McpSession.totalToolCalls() + " tool calls"
                    : " ";
            }
            detailLine.setText(mcp + llmStats());
        }

        /**
         * Keep the LLM console's MCP tools in sync with the server's latest
         * script scan (hot reload). The optional config key
         * {@code llm.mcp.tools} restricts the console to a comma-separated
         * list of tool names; absent or empty means all tools.
         */
        private void syncMcpTools() {
            var server = serverSupplier.get();
            if (server == null || !server.isRunning()) {
                return;
            }
            var filter = LlmChatClient.configProperty("llm.mcp.tools");
            Set<String> allowed = null;
            if (filter != null) {
                allowed = new TreeSet<>();
                for (String part : filter.split(",")) {
                    if (!part.trim().isEmpty()) {
                        allowed.add(part.trim());
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (McpToolDefinition def : server.getToolDefinitions()) {
                if (allowed == null || allowed.contains(def.name())) {
                    names.add(def.name());
                }
            }
            if (names.equals(lastMcpToolNames)) {
                return;
            }
            for (String old : lastMcpToolNames) {
                llm.unregisterTool(old);
            }
            for (McpToolDefinition def : server.getToolDefinitions()) {
                if (!names.contains(def.name())) {
                    continue;
                }
                final var toolDef = def;
                llm.registerTool(new LlmChatClient.Tool() {
                    @Override
                    public String name() {
                        return toolDef.name();
                    }

                    @Override
                    public String description() {
                        return toolDef.description() == null ? "" : toolDef.description();
                    }

                    @Override
                    public Map<String, Object> parametersSchema() {
                        return toolDef.inputSchema() == null
                            ? Map.of("type", "object") : toolDef.inputSchema();
                    }

                    @Override
                    public String execute(Map<String, Object> arguments) {
                        var result = toolDef.handler().call(arguments);
                        var sb = new StringBuilder();
                        for (var tc : result.content()) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(tc.text());
                        }
                        return (result.isError() ? "Error: " : "") + sb;
                    }
                });
            }
            lastMcpToolNames = List.copyOf(names);
            appendLine(names.isEmpty()
                ? "no MCP tools registered for the LLM console"
                : names.size() + " MCP tools registered for the LLM console",
                INFO_COLOR);
        }

        private String llmStats() {
            LlmChatClient.UsageStats u = llm.usageStats();
            String s = "   LLM " + llm.toolCount() + " tools / ctx "
                + u.contextMessages + " msgs / " + human(u.contextChars) + " chars";
            return s + (u.usageReported
                ? "   " + u.promptTokens + " tok in / " + u.completionTokens + " tok out"
                : "   tokens -");
        }

        private static String human(long n) {
            if (n < 1000) {
                return Long.toString(n);
            }
            if (n < 1_000_000) {
                return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
            }
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }

        private void submit() {
            String text = input.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            appendLine("> " + text, USER_COLOR);
            // Read once per turn, not per chunk: the block's open/closed state
            // has to stay consistent for the whole turn, and this still gives
            // the documented "takes effect on the next message" behaviour.
            final boolean showThinking = PluginConfig.flag(PluginConfig.SHOW_THINKING, false);
            // Whether a thinking block is currently open in the pane. The
            // callbacks arrive on the worker thread in order, and every one
            // of them reposts to the EDT through invokeLater, which preserves
            // that order - so plain booleans are enough here.
            final boolean[] thinkingOpen = {false};

            llm.send(text, new LlmChatClient.StreamCallback() {
                @Override
                public void onReasoning(String delta) {
                    if (!showThinking) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (!thinkingOpen[0]) {
                            thinkingOpen[0] = true;
                            appendText("\nthinking: ", THINKING_COLOR, true);
                        }
                        appendText(delta, THINKING_COLOR, true);
                    });
                }

                @Override
                public void onDelta(String delta) {
                    SwingUtilities.invokeLater(() -> {
                        closeThinking(thinkingOpen);
                        appendText(delta, REPLY_COLOR);
                    });
                }

                @Override
                public void onComplete(String fullReply) {
                    SwingUtilities.invokeLater(() -> {
                        closeThinking(thinkingOpen);
                        appendText("\n", REPLY_COLOR);
                    });
                }

                @Override
                public void onError(String message) {
                    SwingUtilities.invokeLater(() -> {
                        closeThinking(thinkingOpen);
                        appendLine("error: " + message, ERROR_COLOR);
                    });
                }

                @Override
                public void onToolCall(String name, String argumentsJson) {
                    if (!showToolCalls()) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        closeThinking(thinkingOpen);
                        appendLine("  [tool] " + name + " " + truncate(argumentsJson, 300), TOOL_COLOR);
                    });
                }

                @Override
                public void onToolResult(String name, String result) {
                    if (!showToolCalls()) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        closeThinking(thinkingOpen);
                        appendLine("  [result] " + truncate(result, 300), TOOL_COLOR);
                    });
                }

            });
        }

        /**
         * Close an open thinking block with a blank line, so the reply that
         * follows never runs into the reasoning above it. Must be called on
         * the EDT, which is where every console mutation happens.
         */
        private void closeThinking(boolean[] open) {
            if (open[0]) {
                open[0] = false;
                appendText("\n", THINKING_COLOR, true);
            }
        }

        /**
         * Whether tool calls and their results are printed. Read per callback
         * rather than cached, so turning it on in the configuration dialog
         * takes effect on the next message. The per-round tool selection is
         * deliberately not shown here: it goes only to the conversation log
         * ({@code llm.log}), since it is diagnostic detail rather than part
         * of the conversation.
         */
        private boolean showToolCalls() {
            return PluginConfig.flag(PluginConfig.SHOW_TOOL_CALLS, false);
        }

        private void clearAll() {
            logPane.setText("");
            llm.reset();
            appendLine("console cleared", INFO_COLOR);
        }

        private void appendText(String text, Color color) {
            appendText(text, color, false);
        }

        /**
         * Append styled text at the end of the pane and follow it with the
         * caret. Every attribute is set explicitly rather than inherited: a
         * {@link javax.swing.text.DefaultStyledDocument} keeps the previous
         * run's attributes at the insertion point, so an italic reasoning run
         * would otherwise bleed italics into the reply that follows it.
         */
        private void appendText(String text, Color color, boolean italic) {
            if (text == null || text.isEmpty()) {
                return;
            }
            try {
                StyledDocument doc = logPane.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, color);
                StyleConstants.setItalic(attrs, italic);
                doc.insertString(doc.getLength(), text, attrs);
                logPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // log is read-only; should not happen
            }
        }

        private void appendLine(String line, Color color) {
            appendText(line + "\n", color);
        }

        private static String truncate(String s, int max) {
            if (s == null) {
                return "";
            }
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }

        @Override
        public Component getWindowComponent() {
            return panel;
        }

        @Override
        public Component getDefaultFocusComponent() {
            return input;
        }
    }
}
