import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpResource

class ModelInfo {

    @McpTool(name = "get_model_info", description = "[GENERIC SYSML] Get the currently open model name, overview of top-level packages, available profiles (from primary and used projects), and list of used projects. A lightweight starting point before drilling into specific elements.")
    Map getModelInfo() {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) {
            return [error: "No model open"]
        }
        def model = project.getPrimaryModel()
        def pkgs = []
        def profileNames = []
        if (model != null) {
            for (child in model.getOwnedElement()) {
                if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package) {
                    pkgs.add(child.getName())
                }
            }
        }

        // Collect all Profile elements across the entire project (including used projects)
        try {
            def allElements = com.nomagic.magicdraw.uml.Finder.byTypeRecursively()
                    .find(project, [com.nomagic.magicdraw.core.model.Element.class] as Class[], true)
            for (el in allElements) {
                def className = el.getClass().getName()
                if (className.contains("ProfileImpl")) {
                    def name = el.getName()
                    if (name && !profileNames.contains(name)) {
                        profileNames.add(name)
                    }
                }
            }
        } catch (ignored) {}

        // Collect used projects and their profiles
        def usedProjects = []
        try {
            def primaryProject = project.getPrimaryProject()
            if (primaryProject != null) {
                def attachedProjects = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProjects(primaryProject)
                if (attachedProjects != null) {
                    for (ap in attachedProjects) {
                        def entry = [name: ap.getName()]

                        // Collect top-level package names from shared packages
                        def sharedPkgs = ap.getSharedPackages()
                        if (sharedPkgs != null) {
                            def pkgNames = []
                            for (sp in sharedPkgs) {
                                pkgNames.add(sp.getName())
                            }
                            entry.packages = pkgNames
                        }

                        usedProjects.add(entry)
                    }
                }
            }
        } catch (ignored) {}

        return [modelName: project.getName(), packages: pkgs, profiles: profileNames, usedProjects: usedProjects]
    }

    /** Recursively collect Profile elements from a model element tree. */
    void collectProfiles(def element, def profileNames) {
        try {
            def className = element.getClass().getName()
            if (className.contains("ProfileImpl")) {
                def name = element.getName()
                if (name && !profileNames.contains(name)) {
                    profileNames.add(name)
                }
            }
            def children = element.getOwnedElement()
            if (children != null) {
                for (child in children) {
                    collectProfiles(child, profileNames)
                }
            }
        } catch (ignored) {}
    }

    @McpResource(
        uri = "cameo://model/summary",
        name = "Model Summary",
        description = "Summary of the currently open Cameo model",
        mimeType = "application/json"
    )
    Map modelSummary() {
        return getModelInfo()
    }
}
