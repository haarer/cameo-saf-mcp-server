import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application

class DiagramPngExport {

    @McpTool(name = "export_diagram_as_png", description = "Export a single diagram as a PNG image file to a directory on the Cameo host filesystem. The diagram is located by element ID. Returns the absolute path of the written PNG file.")
    @McpToolArgument(name = "diagramId", type = "string", description = "Element ID of the Diagram element to export (from find_elements / list_owned_elements results)", required = true)
    @McpToolArgument(name = "fileName", type = "string", description = "Optional output file name (without .png extension). Default: sanitized diagram name.")
    @McpToolArgument(name = "outputDir", type = "string", description = "Absolute directory to store the PNG in (created if missing). Default: C:/tools/opencode/cameo-exports")
    @McpToolArgument(name = "dpi", type = "integer", description = "Export resolution in DPI. Default 96")
    @McpToolArgument(name = "scalePercent", type = "integer", description = "Scale factor in percent. Default 100")
    Map exportDiagramAsPng(Map<String, Object> args) {
        def project = Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]

        String outDir = (args.get("outputDir") ?: "C:/tools/opencode/cameo-exports") as String
        int dpi = (args.get("dpi") ?: 96) as int
        int scale = (args.get("scalePercent") ?: 100) as int

        def diagram = project.getElementByID(args.get("diagramId") as String)
        if (diagram == null) return [error: "Element not found: " + args.get("diagramId")]

        def repr
        try {
            repr = project.getDiagram(diagram)
        } catch (Exception e) {
            return [error: "Could not obtain diagram presentation: " + e.getMessage()]
        }
        if (repr == null) return [error: "No diagram presentation available for: " + (diagram.getName() ?: args.get("diagramId"))]

        String base = (args.get("fileName") ?: (diagram.getName() ?: ("diagram_" + args.get("diagramId")))).replaceAll('[^A-Za-z0-9._-]', '_')
        File dir = new File(outDir)
        if (!dir.exists() && !dir.mkdirs()) {
            return [error: "Could not create output directory: " + outDir]
        }
        File file = new File(dir, base + ".png")

        def imgExporterClass = Class.forName("com.nomagic.magicdraw.export.image.ImageExporter")
        int pngFormat = imgExporterClass.getField("PNG").getInt(null)
        def exportMethod = imgExporterClass.methods.find {
            it.name == "export" && it.parameterCount == 5
            && it.parameters[1].type == int
            && it.parameters[2].type == File
            && it.parameters[3].type == int
            && it.parameters[4].type == int
        }
        if (exportMethod == null) return [error: "No suitable ImageExporter.export overload found"]
        exportMethod.invoke(null, repr, pngFormat, file, dpi, scale)

        if (!file.exists() || file.length() == 0L) {
            return [error: "Export produced no file: " + file.getAbsolutePath()]
        }
        return [
            status: "ok",
            diagram: diagram.getName(),
            id: diagram.getID(),
            file: file.getAbsolutePath(),
            sizeBytes: file.length()
        ]
    }
}
