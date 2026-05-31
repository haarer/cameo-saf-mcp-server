import com.haarer.mcpserver.handlers.McpPrompt

class HelloPrompt {

    @McpPrompt(name = "hello", description = "A simple hello prompt")
    String hello() {
        return "Hello! I am the Cameo MCP Server. I can help you interact with Cameo System Modeler."
    }
}
