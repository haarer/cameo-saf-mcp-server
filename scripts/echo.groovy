import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

class EchoTool {

    @McpTool(name = "echo", description = "Echo back an input message. Use this to verify MCP connectivity and argument passing.")
    @McpToolArgument(name = "message", type = "string", description = "The message to echo back")
    String echo(Map<String, Object> args) {
        def message = args.getOrDefault("message", "hello")
        return "Echo: " + message
    }
}
