// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package com.nomagic.magicdraw.ui;
import com.nomagic.magicdraw.core.Project;
/** Registers and drives the per-project window set. */
public interface ProjectWindowsManager {
    /** MagicDraw calls each registered configurator once per opened project. */
    class ConfiguratorRegistry {
        public static void addConfigurator(ProjectWindowsConfigurator configurator) {}
        public static void removeConfigurator(ProjectWindowsConfigurator configurator) {}
    }
    void addWindow(Project project, ProjectWindow window);
    void updateWindow(Project project, ProjectWindow window);
    void hideWindow(Project project, String windowId);
    void removeWindow(Project project, String windowId);
    void activateWindow(Project project, String windowId);
}
