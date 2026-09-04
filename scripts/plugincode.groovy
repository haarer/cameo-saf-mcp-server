import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

/**
 * plugincode_* tools: develop the on-disk MCP handler scripts themselves —
 * the .groovy files deployed into the Cameo plugin dir that define the MCP
 * tool surface. Complement to the modelcode_* tools (code stored in the
 * model).
 */
class PluginCode {

    static final int MAX_MEMBERS = 400

    @McpTool(name = "plugincode_introspect", description = '''Introspect (reflect on) a Java class loaded in the RUNNING Cameo JVM: list constructors and methods with full signatures. SHARED tool for both Groovy-code development flows:

- Writing on-disk MCP HANDLER scripts (plugincode_* world), and
- Writing in-model code bodies (modelcode_spec_update / modelcode_validation_* world).

PREFER the Javadoc MCP server first: for any signature lookup, use cameo-api_search_docs / cameo-api_lookup_symbol / cameo-api_get_members — the indexed docs are the primary read-only source of truth. Reach for THIS tool only (a) when the signature is not in the Javadoc index, or (b) to confirm how a class actually resolves/loads in the live JVM. Never write code against an unverified API from memory.

Returns for the class: constructors (with parameter types), declared methods, superclass, and implemented interfaces. Use memberFilter to narrow methods by name substring (e.g. memberFilter="closeProject" returns all overloads). Set includeInherited=true to also see inherited public methods (default false = declared only).

className must be fully qualified (e.g. "com.nomagic.magicdraw.core.project.ProjectsManager"). Simple names are NOT resolved - if you only know the simple name, guess the package from context or use cameo-api_search_docs first.''')
    @McpToolArgument(name = "className", type = "string", description = "Fully qualified class name, e.g. 'com.nomagic.magicdraw.core.project.ProjectsManager'", required = true)
    @McpToolArgument(name = "memberFilter", type = "string", description = "Optional substring to filter members by name (e.g. 'closeProject', 'validate')")
    @McpToolArgument(name = "kind", type = "string", description = "'all' (default), 'methods', or 'constructors'")
    @McpToolArgument(name = "includeInherited", type = "boolean", description = "true = include inherited public methods (default false)")
    Map introspect(Map<String, Object> args) {
        def className = args.get("className") as String
        def memberFilter = ((args.get("memberFilter") ?: "") as String)
        def kind = ((args.get("kind") ?: "all") as String).toLowerCase()
        boolean inherited = Boolean.parseBoolean(String.valueOf(args.getOrDefault("includeInherited", "false")))

        if (!className) return [error: "className is required"]

        Class cls = null
        def loaders = []
        def tryLoad = { ClassLoader l ->
            if (l == null || cls != null) return
            try { loaders.add(l.toString()) } catch (ignored) { loaders.add(String.valueOf(l)) }
            try {
                def c = Class.forName(className, false, l)
                if (c != null) cls = c
            } catch (Throwable ignored) {}
        }
        tryLoad(Thread.currentThread().getContextClassLoader())
        tryLoad(this.getClass().getClassLoader())
        def l = this.getClass().getClassLoader()
        int hops = 0
        while (cls == null && l != null && hops++ < 6) {
            tryLoad(l.getParent())
            l = l.getParent()
        }
        tryLoad(ClassLoader.systemClassLoader)

        if (cls == null) {
            return [error: "Class not found in any loader: " + className,
                    triedLoaders: loaders,
                    hint: "Verify the FQN via cameo-api_search_docs / cameo-api_lookup_symbol; the Javadoc index is the second source of truth."]
        }

        def out = [
            class: cls.getName(),
            resolvedFrom: cls.getClassLoader() != null ? cls.getClassLoader().toString() : "bootstrap",
            superclass: cls.getSuperclass() != null ? cls.getSuperclass().getName() : null,
            interfaces: cls.getInterfaces().collect { it.getName() },
            enum: cls.isEnum(),
            abstract: java.lang.reflect.Modifier.isAbstract(cls.getModifiers())
        ]

        boolean wantMethods = (kind == "all" || kind == "methods")
        boolean wantCtors = (kind == "all" || kind == "constructors")

        if (wantCtors) {
            def ctors = cls.getDeclaredConstructors().collect { c ->
                [ctor: shortParams(c.toString())]
            }.sort { it.ctor }
            out.constructors = ctors.take(MAX_MEMBERS)
            out.constructorsTotal = ctors.size()
        }

        if (wantMethods) {
            def methods = (inherited ? cls.getMethods() : cls.getDeclaredMethods()).findAll { m ->
                memberFilter.isEmpty() || m.getName().contains(memberFilter)
            }
            // deduplicate bridge/signature duplicates by toString
            def seen = new HashSet()
            def sigs = []
            for (m in methods.sort { it.getName() }) {
                def s = m.toGenericString()
                if (seen.add(s)) {
                    sigs.add([name: m.getName(), signature: s])
                }
            }
            out.methods = sigs.take(MAX_MEMBERS)
            out.methodsTotal = sigs.size()
        }

        boolean truncated = ((out.constructorsTotal ?: 0) > MAX_MEMBERS) || ((out.methodsTotal ?: 0) > MAX_MEMBERS)
        if (truncated) out.truncated = true
        return out
    }

    /** Strip the leading modifiers/class prefix from Constructor.toString(), keep '(params)' plus throws. */
    String shortParams(String s) {
        int p = s.indexOf('(')
        if (p < 0) return s
        String head = s.substring(0, p)
        // head looks like "public com.pkg.Type(com.pkg.A)" -> keep last token before '('
        String owner = head.tokenize().isEmpty() ? head : head.tokenize().last()
        int close = s.indexOf(')', p)
        if (close < 0) return s
        String paramsPart = s.substring(p, close + 1)
        String tail = s.length() > close + 1 ? s.substring(close + 1) : ""
        return new StringBuilder(owner).append(paramsPart).append(tail).toString()
    }
}
