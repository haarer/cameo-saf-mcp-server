import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application

class ValidationRun {

    @McpTool(name = "validation_run_rules", description = "[VALIDATION] Run specific validation rule Constraints (given by element ID) against the model and return their violations. Omit elementIds to validate from the primary model root recursively. Uses DefaultValidationRuleImpl.run per rule.")
    @McpToolArgument(name = "constraintIds", type = "array", description = "Element IDs of the validation-rule Constraints to execute", required = true)
    @McpToolArgument(name = "elementIds", type = "array", description = "Optional list of target element IDs to validate; omit to validate from the primary model root")
    Map runRules(Map<String, Object> args) {
        def ids = args.get("constraintIds")
        if (!(ids instanceof List) || ((List) ids).isEmpty()) return [error: "constraintIds array is required"]

        def project = Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]

        def rules = []
        for (id in (List) ids) {
            def c = project.getElementByID(id as String)
            if (c != null) rules.add(c)
        }
        if (rules.isEmpty()) return [error: "No rules resolved from constraintIds"]

        def targets = null
        def targetIds = args.get("elementIds")
        if (targetIds instanceof List && !((List) targetIds).isEmpty()) {
            targets = []
            for (id in (List) targetIds) {
                def e = project.getElementByID(id as String)
                if (e != null) targets.add(e)
            }
            if (targets.isEmpty()) return [error: "No target elements resolved"]
        }
        if (targets == null) targets = [project.getPrimaryModel()]

        // Reflection helper: first getter that exists wins.
        def invokeFirst = { obj, List<String> names ->
            for (n in names) {
                try {
                    def m = obj.getClass().getMethod(n)
                    return m.invoke(obj)
                } catch (NoSuchMethodException ignored) {
                } catch (Exception ex) {
                    return "<err " + n + ": " + ex.getMessage() + ">"
                }
            }
            return null
        }

        try {
            def impl = new com.nomagic.magicdraw.validation.DefaultValidationRuleImpl()
            def out = []
            for (rule in rules) {
                def ruleName = (rule instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) ? rule.getName() : "?"
                def res = impl.run(project, rule, targets)
                if (res == null) continue
                for (a in res) {
                    def msg = invokeFirst(a, ["getMessage", "getText", "getName"])
                    def annElems = invokeFirst(a, ["getElements", "getTargets"])
                    def tgtList = []
                    if (annElems instanceof Collection) {
                        for (t in (Collection) annElems) {
                            def te = [
                                id: t.getID(),
                                type: t.getHumanType()
                            ]
                            if (t instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) te.name = t.getName()
                            tgtList.add(te)
                        }
                    } else if (annElems != null) {
                        tgtList.add([raw: String.valueOf(annElems)])
                    }
                    out.add([rule: ruleName, message: String.valueOf(msg), targets: tgtList])
                }
            }
            return [status: "ok", violations: out, count: out.size()]
        } catch (Exception e) {
            return [error: "validation failed: " + e.getMessage()]
        }
    }
}
