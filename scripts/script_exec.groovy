import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application

import org.codehaus.groovy.control.CompilerConfiguration

class ScriptExec {

    @McpTool(name = "rule_eval", description = "[VALIDATION] Execute a script-language Constraint specification directly against target elements, using validation-engine-style bindings: THIS (target element), project, result (holder with set/get). Supports Groovy specs (result is the return value). Use to test validation rule logic without running the full validation UI. Returns per-target pass/fail.")
    @McpToolArgument(name = "constraintId", type = "string", description = "Element ID of the Constraint whose specification should be executed", required = true)
    @McpToolArgument(name = "targetIds", type = "array", description = "Element IDs of targets. Omit together with targetType to scan all Associations in the primary model.")
    @McpToolArgument(name = "targetType", type = "string", description = "Type-name substring used to collect targets when targetIds omitted. Default 'Association'.")
    Map eval(Map<String, Object> args) {
        def constraintId = args.get("constraintId") as String
        if (!constraintId) return [error: "constraintId required"]

        def project = Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]

        def rule = project.getElementByID(constraintId)
        if (rule == null) return [error: "Constraint not found: " + constraintId]

        def spec = null
        try { spec = rule.getSpecification() } catch (ignored) {}
        if (!(spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression)) {
            return [error: "Constraint specification is not a script (OpaqueExpression)"]
        }
        def langs = spec.getLanguage()
        def bodies = spec.getBody()
        if (bodies == null || bodies.isEmpty()) return [error: "Empty specification body"]
        def lang = (langs != null && !langs.isEmpty()) ? langs[0] : "?"
        def body = bodies[0]
        if (!lang.equalsIgnoreCase("Groovy")) {
            return [error: "Unsupported language '" + lang + "' (only Groovy)"]
        }

        // Collect targets
        def targets = []
        def tids = args.get("targetIds")
        if (tids instanceof List && !((List) tids).isEmpty()) {
            for (id in (List) tids) {
                def e = project.getElementByID(id as String)
                if (e != null) targets.add(e)
            }
        } else {
            def ttype = ((args.get("targetType") ?: "Association") as String).toLowerCase()
            def root = project.getPrimaryModel()
            def fi = com.nomagic.magicdraw.uml.Finder.byTypeRecursively()
            def candidates = fi.find(root, null)
            for (c in candidates) {
                try {
                    if (c.getHumanType().toLowerCase().contains(ttype)) targets.add(c)
                } catch (ignored) {}
            }
        }
        if (targets.isEmpty()) return [error: "No target elements"]

        // Pre-compile once, evaluate per target with fresh bindings
        def cc = new CompilerConfiguration()
        def shell = new GroovyShell(new GroovyClassLoader(this.getClass().getClassLoader(), cc), new Binding())
        def script = shell.parse(body)

        def out = []
        int passed = 0
        int failed = 0
        int errors = 0
        for (t in targets) {
            def entry = [id: t.getID()]
            try {
                if (t instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) entry.name = t.getName()
                def b = new Binding()
                b.setVariable("THIS", t)
                b.setVariable("project", project)
                def holder = new groovy.lang.Reference(null)
                b.setVariable("result", holder)
                def sc = shell.parse(body)
                sc.setBinding(b)
                def val = sc.run()
                boolean ok
                if (val instanceof Boolean) ok = (Boolean) val
                else ok = Boolean.parseBoolean(String.valueOf(val))
                entry.result = ok
                if (ok) passed++ else failed++
            } catch (Exception e) {
                entry.error = e.getMessage()
                errors++
            }
            out.add(entry)
        }
        return [status: "ok", language: lang, evaluated: out.size(), passed: passed, failed: failed, errors: errors, details: out]
    }
}
