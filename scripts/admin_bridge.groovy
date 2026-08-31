import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.haarer.saf.mcpserver.protocol.McpSession
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.core.project.ProjectDescriptor

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
