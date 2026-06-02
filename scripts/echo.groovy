import com.haarer.saf.mcpserver.handlers.McpTool

class EchoTool {

    @McpTool(name = "echo", description = "[GENERIC SYSML] Echo back the input message. Simple diagnostic utility to verify MCP connectivity and argument passing.")
    String echo(Map<String, Object> args) {
        def message = args.getOrDefault("message", "hello")
        return "Echo: " + message
    }
}
