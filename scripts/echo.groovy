import com.haarer.mcpserver.handlers.McpTool

class EchoTool {

    @McpTool(name = "echo", description = "Echo back the input message")
    String echo(Map<String, Object> args) {
        def message = args.getOrDefault("message", "hello")
        return "Echo: " + message
    }
}
