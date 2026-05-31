import com.haarer.mcpserver.handlers.McpTool
import com.haarer.mcpserver.handlers.McpResource

class ModelInfo {

    @McpTool(name = "get_model_info", description = "Get model name and overview of used packages and profiles")
    Map getModelInfo() {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) {
            return [error: "No model open"]
        }
        def model = project.getPrimaryModel()
        def pkgs = []
        def profiles = []
        if (model != null) {
            for (child in model.getOwnedElement()) {
                if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package) {
                    pkgs.add(child.getName())
                }
            }
            try {
                for (pa in model.getProfileApplication()) {
                    def p = pa.getAppliedProfile()
                    if (p != null) profiles.add(p.getName())
                }
            } catch (ignored) {}
        }
        return [modelName: project.getName(), packages: pkgs, profiles: profiles]
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
