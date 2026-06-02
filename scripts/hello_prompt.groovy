import com.haarer.saf.mcpserver.handlers.McpPrompt

class HelloPrompt {

    @McpPrompt(name = "hello", description = "A simple hello prompt")
    String hello() {
        return "Hello! I am the Cameo SAF MCP Server. I can help you interact with Cameo System Modeler."
    }
}
