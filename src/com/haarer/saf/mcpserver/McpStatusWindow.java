package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jidesoft.docking.DockContext;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.ui.ProjectWindow;
import com.haarer.saf.mcpserver.protocol.McpSession;
import com.nomagic.magicdraw.ui.ProjectWindowsConfigurator;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.awt.Font;
import java.util.function.Supplier;

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
 *       session count, total tool call count;</li>
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

    public McpStatusWindow(Supplier<CameoMcpServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
    }

    @Override
    public void configure(Project project, ProjectWindowsManager manager) {
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
        manager.addWindow(project, new ProjectWindow(info, new StatusContent(serverSupplier)));
    }

    private static final class StatusContent implements WindowComponentContent {
        private static final Color OK_COLOR = new Color(0, 128, 0);
        private static final Color STOP_COLOR = Color.RED;
        private static final Color USER_COLOR = new Color(70, 70, 70);
        private static final Color REPLY_COLOR = new Color(25, 110, 200);
        private static final Color ERROR_COLOR = new Color(190, 30, 30);
        private static final Color INFO_COLOR = Color.GRAY;

        private final Supplier<CameoMcpServer> serverSupplier;
        private final LlmChatClient llm;
        private final JPanel panel;
        private final JLabel statusLine;
        private final JLabel detailLine;
        private final JTextPane logPane;
        private final JTextField input;
        private final Timer timer;

        StatusContent(Supplier<CameoMcpServer> serverSupplier) {
            this.serverSupplier = serverSupplier;
            this.llm = new LlmChatClient(new ObjectMapper());

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

            // -- text entry + clear --------------------------------------
            input = new JTextField();
            input.addActionListener(e -> submit());
            var clear = new JButton("Clear");
            clear.addActionListener(e -> clearAll());
            var inputRow = new JPanel(new BorderLayout(6, 0));
            inputRow.setOpaque(false);
            inputRow.add(input, BorderLayout.CENTER);
            inputRow.add(clear, BorderLayout.EAST);

            panel = new JPanel(new BorderLayout(0, 0));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            panel.add(statusRow, BorderLayout.NORTH);
            panel.add(logScroll, BorderLayout.CENTER);
            panel.add(inputRow, BorderLayout.SOUTH);
            panel.setMinimumSize(new Dimension(500, 220));

            appendLine("LLM endpoint: " + llm.baseUrl(), INFO_COLOR);
            appendLine("Type a message and press Enter to chat with the LLM.", INFO_COLOR);

            refresh();
            timer = new Timer(1000, e -> refresh());
            timer.start();
        }

        private void refresh() {
            var server = serverSupplier.get();
            if (server == null) {
                statusLine.setText("MCP Server: not started");
                statusLine.setForeground(STOP_COLOR);
                detailLine.setText(" ");
                return;
            }
            boolean running = server.isRunning();
            statusLine.setText("MCP Server: " + (running ? "running" : "stopped"));
            statusLine.setForeground(running ? OK_COLOR : STOP_COLOR);
            if (running) {
                detailLine.setText("http://" + server.getHost() + ":" + server.getPort() + "/mcp"
                    + "   " + server.getToolCount() + " tools"
                    + "   " + server.getActiveSessions() + " sessions"
                    + "   " + McpSession.totalToolCalls() + " tool calls");
            } else {
                detailLine.setText(" ");
            }
        }

        private void submit() {
            String text = input.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            appendLine("> " + text, USER_COLOR);
            llm.send(text, new LlmChatClient.StreamCallback() {
                @Override
                public void onDelta(String delta) {
                    SwingUtilities.invokeLater(() -> appendText(delta, REPLY_COLOR));
                }

                @Override
                public void onComplete(String fullReply) {
                    SwingUtilities.invokeLater(() -> appendText("\n", REPLY_COLOR));
                }

                @Override
                public void onError(String message) {
                    SwingUtilities.invokeLater(() -> appendLine("error: " + message, ERROR_COLOR));
                }
            });
        }

        private void clearAll() {
            logPane.setText("");
            llm.reset();
            appendLine("console cleared", INFO_COLOR);
        }

        private void appendText(String text, Color color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            try {
                StyledDocument doc = logPane.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, color);
                doc.insertString(doc.getLength(), text, attrs);
                logPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // log is read-only; should not happen
            }
        }

        private void appendLine(String line, Color color) {
            appendText(line + "\n", color);
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
