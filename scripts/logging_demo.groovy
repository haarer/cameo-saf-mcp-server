import com.haarer.saf.mcpserver.handlers.McpTool
import com.nomagic.magicdraw.core.Application

class LoggingDemo {

    @McpTool(name = "logging_demo", description = "[GENERIC SYSML] Write log messages to the Cameo notification window. Useful for live debugging during Cameo plugin development.")
    String demo(Map<String, Object> args) {
        def guiLog = Application.getInstance().getGUILog()
        guiLog.log("=== Logging Demo Tool ===")
        guiLog.log("INFO: This is a log message from the MCP server")

        def project = Application.getInstance().getProject()
        guiLog.log("INFO: Project loaded: " + (project != null ? project.getName() : "NONE"))

        return "OK - check Cameo notification window for log output"
    }
}
