// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package com.nomagic.magicdraw.ui;
import com.nomagic.magicdraw.core.Project;
/** Contributes windows to a {@link ProjectWindowsManager}. */
public interface ProjectWindowsConfigurator {
    void configure(Project project, ProjectWindowsManager manager);
}
