import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpResource
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement

class ModelInfo {

    List getModelRoots(def project) {
        def roots = []
        try {
            def models = project.getModels()
            if (models != null) roots.addAll(models)
        } catch (ignored) {}
        if (roots.isEmpty()) {
            def pm = project.getPrimaryModel()
            if (pm != null) roots.add(pm)
        }
        return roots
    }

    Map rootProjectInfo(def project, def root) {
        def primary = null
        try { primary = project.getPrimaryModel() } catch (ignored) {}
        if (root == primary) {
            def info = [name: project.getName(), primary: true, writable: true]
            try {
                def desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(project)
                if (desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor) {
                    info.writable = false
                    info.remote = true
                    try { info.location = desc.getURI()?.toString() ?: "" } catch (ignored) {}
                } else if (desc != null) {
                    try {
                        def uri = desc.getURI()
                        if (uri != null) info.location = uri.toString()
                        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                            def f = new File(uri)
                            if (f.exists() && !f.canWrite()) info.writable = false
                        }
                    } catch (ignored) {}
                }
            } catch (ignored) {}
            return info
        }
        def info = [name: "", primary: false, writable: true]
        try {
            def wrapper = com.nomagic.magicdraw.core.ProjectUtilities.getProject(root.eResource())
            if (wrapper != null) {
                info.name = wrapper.getName()
                def desc = null
                try { desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(wrapper) } catch (ignored) {}
                if (desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor) {
                    info.writable = false
                    info.remote = true
                    try { info.location = desc.getURI()?.toString() ?: "" } catch (ignored) {}
                } else if (desc != null) {
                    try {
                        def uri = desc.getURI()
                        if (uri != null) info.location = uri.toString()
                        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                            def f = new File(uri)
                            if (f.exists() && !f.canWrite()) info.writable = false
                        }
                    } catch (ignored) {}
                }
            }
        } catch (ignored) {}
        if (!info.name) info.name = root.getName() ?: "module"
        return info
    }

    @McpTool(name = "get_model_info", description = "[GENERIC SYSML] Get the currently open model name, overview of top-level packages, available profiles (from primary and used projects), all model roots with owning project and writability (modelRoots), and list of used projects/modules with their packages, location, and writable flag. A lightweight starting point before drilling into specific elements. Used projects are typically read-only; check the writable flag before modifying elements.")
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

        // All model roots: primary model plus one root per used project/module
        def modelRoots = []
        for (root in getModelRoots(project)) {
            try {
                def info = rootProjectInfo(project, root)
                def entry = [
                    name: root.getName() ?: "",
                    id: root.getID(),
                    project: info.name,
                    primary: info.primary,
                    writable: info.writable
                ]
                if (info.remote) entry.remote = true
                if (info.location) entry.location = info.location
                modelRoots.add(entry)
            } catch (ignored) {}
        }

        // Used projects derived from non-primary roots
        def usedProjects = []
        for (root in getModelRoots(project)) {
            def info = null
            try { info = rootProjectInfo(project, root) } catch (ignored) {}
            if (info == null || info.primary) continue
            def pkgNames = []
            try {
                for (child in root.getOwnedElement()) {
                    pkgNames.add(child.getName())
                }
            } catch (ignored) {}
            def entry = [name: info.name, packages: pkgNames, mountRootId: root.getID(), writable: info.writable]
            if (info.remote) entry.remote = true
            if (info.location) entry.location = info.location
            usedProjects.add(entry)
        }

        // Attached modules incl. reshared/embedded ones (e.g. former TWC shared packages
        // mounted into the primary tree). Enumerated via their shared/mounted packages.
        def modules = []
        def warnings = []
        try {
            def primaryProject = project.getPrimaryProject()
            def sharedPkgs = com.nomagic.magicdraw.core.ProjectUtilities.getSharedPackagesIncludingResharedRecursively(primaryProject)
            def byModule = [:] as LinkedHashMap
            for (sp in sharedPkgs) {
                try {
                    def ap = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProject(sp)
                    if (ap == null) continue
                    def key = ap.getName()
                    def entry = byModule.get(key)
                    if (entry == null) {
                        entry = [name: ap.getName(), packages: []]
                        try { entry.readOnly = ap.isReadOnly() } catch (Exception e1) { if (!warnings.contains("isReadOnly() unavailable")) warnings.add("isReadOnly() unavailable") }
                        try {
                            def u = ap.getURI()
                            if (u != null) entry.location = u.toString()
                        } catch (Exception e2) { if (!warnings.contains("getURI() unavailable")) warnings.add("getURI() unavailable") }
                        byModule.put(key, entry)
                    }
                    entry.packages.add(sp.getName())
                } catch (Exception e) {
                    def w = "shared package mapping failed: " + e.getMessage()
                    if (!warnings.contains(w)) warnings.add(w)
                }
            }
            modules.addAll(byModule.values())
        } catch (Exception e) {
            warnings.add("module enumeration failed: " + e.getMessage())
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
        def legacyUsedProjects = []
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

                        legacyUsedProjects.add(entry)
                    }
                }
            }
        } catch (ignored) {}

        return [modelName: project.getName(), packages: pkgs, profiles: profileNames, modelRoots: modelRoots, usedProjects: usedProjects, attachedProjects: legacyUsedProjects, modules: modules, warnings: warnings]
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

    @McpResource(
        uri = "cameo://project",
        name = "Project",
        description = "Currently open project info (same payload as the get_model_info tool)",
        mimeType = "application/json"
    )
    Map projectResource() {
        return getModelInfo()
    }

    @McpResource(
        uri = "cameo://diagram/{id}",
        name = "Diagram",
        description = "Summary of a diagram by element ID plus the model elements presented on it, unwrapped from their diagram views. SAF kind/domain mapping is intentionally not resolved here — use the saf_* tools for SAF semantics.",
        mimeType = "application/json"
    )
    Map diagramById(Map<String, String> params) {
        def id = params.get("id")
        if (!id) return [error: "id is required"]
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]
        def d = project.getElementByID(id)
        if (d == null) return [error: "Diagram not found: " + id]

        def out = [id: id, name: "", type: "Diagram"]
        try { out.name = d.getName() ?: "" } catch (ignored) {}
        try {
            def t = d.getHumanType()
            if (t) out.type = t
        } catch (ignored) {}
        try {
            def qn = d.getQualifiedName()
            if (qn) out.qualifiedName = qn
        } catch (ignored) {}

        def elements = []
        def used = null
        try { used = d.getUsedModelElements() } catch (ignored) {}
        if (used != null) {
            for (el in used) {
                try {
                    if (elements.size() >= 300) break
                    if (el == null) continue
                    def e = [id: el.getID(), name: "", type: "Element"]
                    try { e.name = el instanceof NamedElement ? (el.getName() ?: "") : "" } catch (ignored) {}
                    try {
                        def t = el.getHumanType()
                        if (t) e.type = t
                    } catch (ignored) {}
                    elements.add(e)
                } catch (ignored) {}
            }
        }
        out.elementCount = elements.size()
        out.elements = elements
        return out
    }
}
