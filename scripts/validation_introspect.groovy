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

    @McpTool(name = "debug_stereotype_tags", description = "[DEBUG] Dump every applied stereotype's property values of an element by ID.")
    @com.haarer.saf.mcpserver.handlers.McpToolArgument(name = "elementId", type = "string", description = "Element ID", required = true)
    Map dumpTags(Map<String, Object> args) {
        def id = args.get("elementId") as String
        if (!id) return [error: "elementId required"]
        def project = Application.getInstance().getProject()
        def el = project.getElementByID(id)
        if (el == null) return [error: "not found: " + id]
        def tags = []
        for (st in com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotypes(el)) {
            try {
                for (p in st.getAttribute()) {
                    def vals
                    try {
                        vals = com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotypePropertyValue(el, st, p.getName())
                    } catch (Exception inner) {
                        vals = ["<err> " + inner.getMessage()]
                    }
                    tags.add([stereotype: st.getName(), property: p.getName(), values: vals.collect { String.valueOf(it) }])
                }
            } catch (ignored) {}
        }
        return [id: id, name: (el instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement ? el.getName() : ""), tags: tags]
    }
}
