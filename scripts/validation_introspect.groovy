import com.haarer.saf.mcpserver.handlers.McpTool
import com.nomagic.magicdraw.core.Application

class ValidationIntrospect {

    @McpTool(name = "validation_introspect", description = "[VALIDATION] Internal: dump runtime signatures of validation execution classes and available severity literals. Debug helper.")
    Map introspect(Map<String, Object> args) {
        def out = [:]
        def dumpMethods = { Class c, String filter ->
            try {
                c.getDeclaredMethods().findAll { m -> filter == null || m.getName().contains(filter) }.collect { it.toString() }
            } catch (Exception e) { ["<err> " + e.getMessage()] }
        }
        def dumpCtors = { Class c ->
            try { c.getDeclaredConstructors().collect { it.toString() } } catch (Exception e) { ["<err> " + e.getMessage()] }
        }
        def load = { String n -> try { Class.forName(n) } catch (Exception e) { null } }

        out.defaultRuleRun = dumpMethods(load("com.nomagic.magicdraw.validation.DefaultValidationRuleImpl"), "run")
        out.helperValidate = dumpMethods(load("com.nomagic.magicdraw.validation.ValidationHelper"), "validate")

        // Severity enumeration literals from the validation profile
        try {
            def project = Application.getInstance().getProject()
            def sev = []
            def scan = { el ->
                try {
                    if (el instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Enumeration &&
                        (el.getName() == "SeverityKind" || el.getName().contains("Severity"))) {
                        el.getOwnedLiteral().each { l -> sev.add(el.getName() + "::" + l.getName() + " (" + l.getID() + ")") }
                    }
                } catch (ignored) {}
                return false
            }
            // find enumerations by name across the project
            def fi = com.nomagic.magicdraw.uml.Finder.byTypeRecursively()
            def all = fi.find(project, [com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Enumeration.class] as Class[], true)
            for (e in all) {
                if (e.getName() == "SeverityKind" || e.getName().contains("Severity")) {
                    e.getOwnedLiteral().each { l -> sev.add(e.getName() + "::" + l.getName()) }
                }
            }
            out.severities = sev
        } catch (Exception ex) {
            out.severities = ["<err> " + ex.getMessage()]
        }

        return out
    }
}
