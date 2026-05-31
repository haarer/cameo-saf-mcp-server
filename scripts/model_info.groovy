import com.haarer.mcpserver.handlers.McpTool
import com.haarer.mcpserver.handlers.McpResource
import com.nomagic.magicdraw.core.Application
import groovy.json.JsonOutput

class ModelInfo {

    @McpTool(name = "get_model_name", description = "Get the name of the currently open model")
    String getModelName() {
        def project = Application.getInstance().getProject()
        return project != null ? project.getName() : "No model open"
    }

    @McpResource(
        uri = "cameo://model/summary",
        name = "Model Summary",
        description = "Summary of the currently open Cameo model",
        mimeType = "application/json"
    )
    String modelSummary() {
        def project = Application.getInstance().getProject()
        if (project == null) {
            return JsonOutput.toJson([error: "No model open"])
        }
        def model = project.getPrimaryModel()
        def elementCount = 0
        if (model != null) {
            for (child in model.getOwnedElement()) {
                if (child != null) elementCount++
            }
        }
        return JsonOutput.toJson([
            name: project.getName(),
            primaryModel: model?.getName(),
            topLevelElementCount: elementCount
        ])
    }
}
