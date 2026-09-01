import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.haarer.saf.mcpserver.protocol.McpSession
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.modules.ModulesService
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.core.project.ProjectDescriptor
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile

class AdminBridge {

    static final String PATH_HINT = "Note: paths are resolved on the HOST running Cameo, not inside a dev container. Container path /workspace/... usually maps to host /home/mac/opencode/workspace/..."

    @McpTool(name = "admin_load_model", description = "[ADMIN] Load a model from an mdzip file path. Closes the currently open model first if any. Returns the loaded model name or an error message.")
    @McpToolArgument(name = "path", type = "string", description = "Absolute HOST path to the .mdzip model file to load (the Cameo process resolves this path, e.g. /home/mac/opencode/workspace/...)")
    Map loadModel(Map<String, Object> args) {
        def path = args.get("path")
        if (path == null || path.trim().isEmpty()) {
            return [error: "path argument is required"]
        }

        def file = new File(path)
        if (!file.exists()) {
            return [error: "File not found: " + path, hint: PATH_HINT]
        }
        if (!file.canRead()) {
            return [error: "File not readable: " + path, hint: PATH_HINT]
        }

        try {
            def app = Application.getInstance()
            def projectsManager = app.getProjectsManager()

            // Close currently open project if any
            def currentProject = app.getProject()
            if (currentProject != null) {
                projectsManager.closeProject(currentProject)
            }

            // Create descriptor and load. createProjectDescriptor expects a location
            // string (URI); plain filesystem paths must be converted first.
            def descriptor = null
            try {
                descriptor = ProjectDescriptorsFactory.createProjectDescriptor(file.toURI().toString())
            } catch (Exception ignored) {
                descriptor = null
            }
            if (descriptor == null) {
                // Fallback: single-File local descriptor overload, if present.
                try {
                    descriptor = ProjectDescriptorsFactory.createLocalProjectDescriptor(file)
                } catch (Exception ignored2) {
                    descriptor = null
                }
            }
            if (descriptor == null) {
                return [error: "Failed to create project descriptor for: " + path]
            }

            projectsManager.loadProject(descriptor, true)
            def loaded = app.getProject()
            if (loaded == null) {
                return [error: "Project loaded but no active project found"]
            }

            return [
                status: "ok",
                modelName: loaded.getName(),
                fileName: file.getName(),
                filePath: file.getAbsolutePath()
            ]
        } catch (Exception e) {
            return [error: "Failed to load model: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_save_model", description = "[ADMIN] Save the currently open model to its file location. Returns the saved model name or an error message.")
    Map saveModel() {
        try {
            def app = Application.getInstance()
            def project = app.getProject()
            if (project == null) {
                return [message: "No model is currently open"]
            }
            def projectsManager = app.getProjectsManager()
            try {
                def desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(project)
                if (desc == null) {
                    return [error: "No project descriptor for: " + project.getName()]
                }
                projectsManager.saveProject(desc, true)
            } catch (Exception inner) {
                return [error: "Failed to save model: " + inner.getMessage()]
            }
            return [
                status: "ok",
                savedModel: project.getName()
            ]
        } catch (Exception e) {
            return [error: "Failed to save model: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_close_model", description = "[ADMIN] Close the currently open model. Returns the name of the closed model or a message if none was open.")
    Map closeModel() {
        try {
            def app = Application.getInstance()
            def project = app.getProject()
            if (project == null) {
                return [message: "No model is currently open"]
            }

            def modelName = project.getName()
            def projectsManager = app.getProjectsManager()
            projectsManager.closeProject(project)

            return [
                status: "ok",
                closedModel: modelName
            ]
        } catch (Exception e) {
            return [error: "Failed to close model: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_get_model_status", description = "[ADMIN] Get the status of the currently open model. Returns model name, file path, and element count, or a message if none is open.")
    Map getModelStatus() {
        try {
            def app = Application.getInstance()
            def project = app.getProject()
            if (project == null) {
                return [message: "No model is currently open"]
            }

            def model = project.getPrimaryModel()
            def elementCount = 0
            def topLevelPackages = []
            if (model != null) {
                for (child in model.getOwnedElement()) {
                    elementCount++
                    if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package) {
                        topLevelPackages.add(child.getName())
                    }
                }
            }

            return [
                status: "ok",
                modelName: project.getName(),
                primaryModelName: model != null ? model.getName() : null,
                topLevelPackages: topLevelPackages,
                elementCount: elementCount,
                fileName: project.getFileName()
            ]
        } catch (Exception e) {
            return [error: "Failed to get model status: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_reset_model", description = "[ADMIN] Reload a model from its mdzip file. Closes the current model and loads a fresh copy from the given path. Useful for resetting model state between test runs.")
    @McpToolArgument(name = "path", type = "string", description = "Absolute HOST path to the .mdzip model file to reload (the Cameo process resolves this path, e.g. /home/mac/opencode/workspace/...)")
    Map resetModel(Map<String, Object> args) {
        def path = args.get("path")
        if (path == null || path.trim().isEmpty()) {
            return [error: "path argument is required"]
        }

        def file = new File(path)
        if (!file.exists()) {
            return [error: "File not found: " + path, hint: PATH_HINT]
        }

        try {
            def app = Application.getInstance()
            def projectsManager = app.getProjectsManager()

            // Close current project if any
            def currentProject = app.getProject()
            if (currentProject != null) {
                projectsManager.closeProject(currentProject)
            }

            // Load fresh
            def descriptor = null
            try {
                descriptor = ProjectDescriptorsFactory.createProjectDescriptor(file.toURI().toString())
            } catch (Exception ignored) {
                descriptor = null
            }
            if (descriptor == null) {
                try {
                    descriptor = ProjectDescriptorsFactory.createLocalProjectDescriptor(file)
                } catch (Exception ignored2) {
                    descriptor = null
                }
            }
            if (descriptor == null) {
                return [error: "Failed to create project descriptor for: " + path]
            }

            projectsManager.loadProject(descriptor, true)
            def loaded = app.getProject()
            if (loaded == null) {
                return [error: "Model loaded but no active project found"]
            }

            return [
                status: "ok",
                modelName: loaded.getName(),
                fileName: file.getName(),
                filePath: file.getAbsolutePath()
            ]
        } catch (Exception e) {
            return [error: "Failed to reset model: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_create_model", description = "[ADMIN] Create a brand new empty model (project) in Cameo and make it the active project. Optionally save it immediately to a .mdzip path on the host. Use admin_apply_profile afterwards to attach profiles (e.g. SAF_Profile).")
    @McpToolArgument(name = "name", type = "string", description = "Name for the new model/project (also used as the primary model name unless primaryModelName is given)", required = true)
    @McpToolArgument(name = "savePath", type = "string", description = "Optional absolute HOST path to save the new model as a .mdzip (e.g. /home/mac/opencode/workspace/.../NewModel.mdzip). If omitted the model exists only in memory until saved with admin_save_model.")
    @McpToolArgument(name = "primaryModelName", type = "string", description = "Optional name for the primary (root) model element. Defaults to 'name'.")
    Map createModel(Map<String, Object> args) {
        def name = args.get("name") as String
        def savePath = args.get("savePath") as String
        def primaryModelName = args.get("primaryModelName") as String
        if (name == null || name.trim().isEmpty()) {
            return [error: "name is required"]
        }

        try {
            def app = Application.getInstance()
            def pm = app.getProjectsManager()

            def project = pm.createProject()
            if (project == null) {
                return [error: "Failed to create project"]
            }
            pm.setActiveProject(project)

            project.setName(name)
            def primary = project.getPrimaryModel()
            if (primary != null) {
                primary.setName(primaryModelName != null && !primaryModelName.trim().isEmpty() ? primaryModelName : name)
            }

            def savedPath = null
            if (savePath != null && !savePath.trim().isEmpty()) {
                def outFile = new File(savePath)
                try {
                    outFile.getParentFile().mkdirs()
                } catch (ignored) {}
                def descriptor = ProjectDescriptorsFactory.createLocalProjectDescriptor(project, outFile)
                if (descriptor == null) {
                    return [error: "Failed to create save descriptor for: " + savePath, hint: PATH_HINT, modelName: project.getName()]
                }
                pm.saveProject(descriptor, true)
                savedPath = outFile.getAbsolutePath()
            }

            def elementCount = 0
            if (primary != null) {
                elementCount = primary.getOwnedElement().size()
            }

            return [
                status: "ok",
                modelName: project.getName(),
                primaryModelName: primary != null ? primary.getName() : null,
                elementCount: elementCount,
                savedPath: savedPath,
                hasProfilesApplied: false
            ]
        } catch (Exception e) {
            return [error: "Failed to create model: " + e.getMessage()]
        }
    }

    @McpTool(name = "admin_apply_profile", description = "[ADMIN] Attach a profile to the currently open model. Loads the profile .mdzip as a module, then applies it (with its dependent profiles) to the primary model so its stereotypes become usable. When only a profile name is given, the file is resolved under the Cameo install's profiles/ (or modelLibraries/) directory — no host path needed.")
    @McpToolArgument(name = "profile", type = "string", description = "Profile name to locate in the Cameo install's profiles/ (or modelLibraries/) directory, e.g. 'SAF_Profile', 'SysML', 'UAF'. One of 'profile' or 'profilePath' is required.")
    @McpToolArgument(name = "profilePath", type = "string", description = "Optional absolute HOST path to the profile .mdzip file. If omitted, 'profile' is resolved under <Cameo install>/profiles/ and <Cameo install>/modelLibraries/.")
    @McpToolArgument(name = "name", type = "string", description = "Optional profile name to apply when profilePath is given (defaults to the file name).")
    Map applyProfile(Map<String, Object> args) {
        def profileName = args.get("profile") as String
        def profilePath = args.get("profilePath") as String
        def nameOverride = args.get("name") as String

        if ((profileName == null || profileName.trim().isEmpty()) && (profilePath == null || profilePath.trim().isEmpty())) {
            return [error: "Either 'profile' (name) or 'profilePath' is required"]
        }

        def app = Application.getInstance()
        def project = app.getProject()
        if (project == null) {
            return [error: "No model is currently open. Create or load one first (admin_create_model / admin_load_model)."]
        }

        // 1. Locate the profile .mdzip on the HOST (explicit path wins over name-based resolution).
        def file = null
        if (profilePath != null && !profilePath.trim().isEmpty()) {
            file = new File(profilePath)
            if (!file.exists()) {
                return [error: "File not found: " + profilePath, hint: PATH_HINT]
            }
        } else {
            def baseName = profileName.trim().replaceAll(/\.mdzip$/, "")
            def installRoot = null
            try {
                installRoot = Application.environment()?.getInstallRoot()
            } catch (ignored) {}
            def searchDirs = ["profiles", "modelLibraries"]
            for (sub in searchDirs) {
                if (installRoot == null) break
                for (candidate in [baseName + ".mdzip", baseName.toLowerCase() + ".mdzip"]) {
                    def f = new File(installRoot, sub + File.separator + candidate)
                    if (f.exists()) {
                        file = f
                        break
                    }
                }
                if (file != null) break
            }
            if (file == null) {
                return [error: "Could not locate profile '" + profileName + "' under Cameo install" + (installRoot ? " (" + installRoot + ")" : "") + " in profiles/ or modelLibraries/. Pass an explicit profilePath instead.", hint: PATH_HINT]
            }
        }

        // 2. Attach the profile project as a module of the open project (allowUI=false: no dialogs).
        def attached = ModulesService.findOrLoadLocalModule(project, file.getAbsolutePath(), false)
        if (attached == null) {
            return [error: "Failed to attach profile module: " + file.getAbsolutePath() + " (already attached or unresolved dependencies)."]
        }

        // 3. Pick the target profile and collect its dependency closure (dependencies apply first).
        def requested = nameOverride != null && !nameOverride.trim().isEmpty()
            ? nameOverride.trim()
            : (profileName != null && !profileName.trim().isEmpty() ? profileName.trim().replaceAll(/\.mdzip$/, "") : file.getName().replaceAll(/\.mdzip$/, ""))
        def target = StereotypesHelper.getProfile(project, requested)
        if (target == null) {
            def loaded = StereotypesHelper.getAllProfiles(project).collect { it.getName() }.sort().join(", ")
            return [error: "Profile '" + requested + "' not found among loaded profiles after attaching " + file.getName() + ". Loaded profiles: " + loaded]
        }

        def toApply = []
        def visited = new HashSet()
        Closure collectDeps
        collectDeps = { Profile p ->
            if (visited.contains(p)) return
            visited.add(p)
            for (dep in StereotypesHelper.getDependingProfiles(p)) {
                collectDeps(dep)
            }
            toApply.add(p)
        }
        collectDeps(target)

        def primary = project.getPrimaryModel()
        if (primary == null) {
            return [error: "Project has no primary model"]
        }

        // 4. Apply each selected profile (dependencies first), skipping already-applied ones.
        def applied = []
        def skipped = []
        def sm = SessionManager.getInstance()
        sm.createSession(project, "admin_apply_profile")
        try {
            def appliedNames = new HashSet(StereotypesHelper.getAppliedProfiles(primary).collect { it.getName() })
            for (p in toApply) {
                if (appliedNames.contains(p.getName())) {
                    skipped.add(p.getName())
                    continue
                }
                if (!StereotypesHelper.canApplyProfile(primary, p)) {
                    skipped.add(p.getName() + " (cannot apply)")
                    continue
                }
                StereotypesHelper.applyProfile(primary, p)
                applied.add(p.getName())
                appliedNames.add(p.getName())
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to apply profile: " + e.getMessage()]
        }

        return [
            status: "ok",
            modelName: project.getName(),
            attachedModule: file.getAbsolutePath(),
            appliedProfiles: applied,
            alreadyApplied: skipped,
            loadedProfileCount: StereotypesHelper.getAllProfiles(project).size()
        ]
    }

    @McpTool(name = "admin_set_enabled_tools", description = "[ADMIN] Restrict which MCP tools are visible and callable. Pass an array of tool names. Only those tools will appear in tools/list and be accepted by tools/call. Call with an empty array or omit to restore all tools.")
    @McpToolArgument(name = "tools", type = "array", description = "Array of tool names to enable (e.g. [\"saf_create_element\", \"saf_create_relationship\"]). Empty or null restores all tools.")
    Map setEnabledTools(Map<String, Object> args) {
        def tools = args.get("tools")
        if (tools == null || (tools instanceof List && tools.isEmpty())) {
            McpSession.clearEnabledTools()
            return [status: "ok", message: "All tools enabled", enabledCount: -1]
        }
        if (tools instanceof List) {
            def toolSet = new LinkedHashSet(tools)
            McpSession.setEnabledTools(toolSet)
            return [status: "ok", enabledCount: toolSet.size(), tools: toolSet]
        }
        return [error: "tools must be an array of tool name strings"]
    }

    @McpTool(name = "admin_get_enabled_tools", description = "[ADMIN] Get the currently enabled tool set. Returns the list of enabled tool names, or a message indicating all tools are enabled.")
    Map getEnabledTools() {
        def enabled = McpSession.getEnabledTools()
        if (enabled == null) {
            return [status: "ok", message: "All tools are enabled", enabledCount: -1]
        }
        return [status: "ok", enabledCount: enabled.size(), tools: enabled]
    }

}
