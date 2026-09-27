package com.haarer.saf.mcpserver;

import com.jidesoft.docking.DockContext;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.ui.ProjectWindow;
import com.haarer.saf.mcpserver.protocol.McpSession;
import com.nomagic.magicdraw.ui.ProjectWindowsConfigurator;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.function.Supplier;

/**
 * Spike: a window inside the Cameo main frame showing MCP server status.
 *
 * <p>Registered once via {@link ProjectWindowsManager.ConfiguratorRegistry};
 * MagicDraw calls {@link #configure} when a project is opened and the resulting
 * window docks at the bottom of the main frame, refreshing every second.</p>
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
        private final JPanel panel;
        private final JLabel statusLine;
        private final JLabel endpointLine;
        private final JLabel toolsLine;
        private final JLabel sessionsLine;
        private final JLabel callsLine;
        private final Supplier<CameoMcpServer> serverSupplier;
        private final Timer timer;

        StatusContent(Supplier<CameoMcpServer> serverSupplier) {
            this.serverSupplier = serverSupplier;
            statusLine = line("MCP Server: ...");
            endpointLine = line("Endpoint: ...");
            toolsLine = line("Tools: ...");
            sessionsLine = line("Sessions: ...");
            callsLine = line("Tool calls: ...");

            panel = new JPanel();
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            panel.add(statusLine);
            panel.add(Box.createVerticalStrut(2));
            panel.add(endpointLine);
            panel.add(toolsLine);
            panel.add(sessionsLine);
            panel.add(callsLine);

            refresh();
            timer = new Timer(1000, e -> refresh());
            timer.start();
        }

        private static JLabel line(String text) {
            var label = new JLabel(text);
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
            return label;
        }

        private void refresh() {
            var server = serverSupplier.get();
            if (server == null) {
                statusLine.setText("MCP Server: not started");
                statusLine.setForeground(Color.RED);
                endpointLine.setText("Endpoint: n/a");
                toolsLine.setText("Tools: n/a");
                sessionsLine.setText("Sessions: n/a");
                callsLine.setText("Tool calls: n/a");
                return;
            }
            boolean running = server.isRunning();
            statusLine.setText("MCP Server: " + (running ? "running" : "stopped"));
            statusLine.setForeground(running ? new Color(0, 128, 0) : Color.RED);
            endpointLine.setText("Endpoint: http://" + server.getHost() + ":" + server.getPort() + "/mcp");
            toolsLine.setText("Tools: " + server.getToolCount());
            sessionsLine.setText("Sessions: " + server.getActiveSessions());
            callsLine.setText("Tool calls: " + McpSession.totalToolCalls());
        }

        @Override
        public Component getWindowComponent() {
            return panel;
        }

        @Override
        public Component getDefaultFocusComponent() {
            return panel;
        }
    }
}
