import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application

import org.codehaus.groovy.control.CompilerConfiguration

/**
 * modelcode_* tools: author and execute executable-code bodies stored IN the
 * model (Constraints, OpaqueExpressions, OpaqueBehaviors — validation rules,
 * simulation behaviors, document-generator expressions). The execution
 * environment differs per sub-case, so each sub-case has its own tool:
 *
 *   modelcode_spec_update       — write/read code in any model element
 *   modelcode_validation_run    — run a rule through the real validation engine
 *   modelcode_validation_eval   — debug a single rule via GroovyShell
 *
 * For developing the on-disk MCP handler scripts themselves (introspection,
 * deploy), see the plugincode_* tools instead (scripts/plugincode.groovy).
 */
class ModelCode {

    def getProject() {
        def proj = Application.getInstance().getProject()
        if (proj == null) throw new RuntimeException("No model open")
        return proj
    }

    def resolveElement(String id) {
        if (id == null || id.isEmpty()) return null
        return getProject().getElementByID(id)
    }

    def getFactory() {
        return getProject().getElementsFactory()
    }

    Map writableCheck(def element) {
        try {
            def ap = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProject(element)
            if (ap == null) return null
            boolean apRo = false
            try { apRo = ap.isReadOnly() } catch (ignored) {}
            if (apRo) {
                def pname = ap.getName() ?: "module"
                return [error: "Element belongs to used project '" + pname + "' which is read-only. Used projects are typically read-only; ask the user how to proceed."]
            }
            def desc = null
            try { desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(ap) } catch (ignored) {}
            boolean remote = desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor
            boolean fileRo = false
            String location = ""
            if (!remote && desc != null) {
                try {
                    def uri = desc.getURI()
                    location = uri != null ? uri.toString() : ""
                    if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                        def f = new File(uri)
                        if (f.exists() && !f.canWrite()) fileRo = true
                    }
                } catch (ignored) {}
            } else if (remote) {
                try { location = desc.getURI()?.toString() ?: "" } catch (ignored) {}
            }
            if (remote || fileRo) {
                def pname = ap.getName() ?: "module"
                return [error: "Element belongs to used project '" + pname + "' which is read-only" + (remote ? " (remote/TWC)" : " (file not writable)") + ". Used projects are typically read-only; ask the user how to proceed."]
            }
        } catch (ignored) {}
        return null
    }

    @McpTool(name = "modelcode_spec_update", description = '''Write executable code (or an expression body) into a model element. This is the generic authoring tool for code STORED IN THE MODEL. It replaces/creates the ValueSpecification of a Constraint as an OpaqueExpression with one language/body pair, and also works directly on OpaqueExpression and OpaqueBehavior elements (sets their language/body lists).

USE THIS for writing any in-model Groovy/OCL/etc. body: validation constraint rules, OpaqueBehavior simulation bodies, or SAF document-generator OpaqueExpressions. To find candidates first, use find_elements_by_type with type='Constraint' (optionally specLanguage='Groovy') or get_element_details. To read the current body, see get_element_details output '<language>\n<body>' format.

AFTER writing a validation rule, run it with modelcode_validation_run (real engine) or debug it with modelcode_validation_eval (per-target pass/fail). For on-disk MCP handler scripts (not model content), use the plugincode_* tools instead.''')
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the Constraint (or of an OpaqueExpression/OpaqueBehavior)", required = true)
    @McpToolArgument(name = "language", type = "string", description = "Language tag for the body (e.g. 'Groovy', 'Jython', 'OCL2.0', 'English'). Default: 'Groovy'.")
    @McpToolArgument(name = "body", type = "string", description = "New specification body text (code)", required = true)
    Map updateSpecification(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def language = (args.get("language") ?: "Groovy") as String
        def body = args.get("body") as String

        if (!elementId) return [error: "elementId is required"]
        if (body == null) return [error: "body is required"]

        def project = getProject()
        def element = project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        // Resolve the value specification holder.
        def holder = null
        if (element instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) {
            holder = element
        } else if (element instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression ||
                   element instanceof com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior) {
            holder = element
        } else {
            return [error: "Element is not a Constraint, OpaqueExpression or OpaqueBehavior: " + element.getHumanType()]
        }

        def sm = com.nomagic.magicdraw.openapi.uml.SessionManager.getInstance()
        // language/body are EMF multi-valued features without generated setters;
        // mutate them reflectively via eSet + UMLPackage literals.
        def LIT = com.nomagic.uml2.ext.magicdraw.metadata.UMLPackage.Literals
        sm.createSession(project, "modelcode_spec_update")
        try {
            if (holder instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) {
                def constraint = (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) holder
                def spec = null
                try { spec = constraint.getSpecification() } catch (ignored) {}
                if (!(spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression)) {
                    // Replace LiteralString/Expression/plain spec with a fresh OpaqueExpression
                    def oe = getFactory().createOpaqueExpressionInstance()
                    com.nomagic.magicdraw.openapi.uml.ModelElementsManager.getInstance().addElement(oe, constraint)
                    constraint.setSpecification(oe)
                    spec = oe
                }
                spec.eSet(LIT.OPAQUE_EXPRESSION__LANGUAGE, [language])
                spec.eSet(LIT.OPAQUE_EXPRESSION__BODY, [body])
            } else if (holder instanceof com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior) {
                holder.eSet(LIT.OPAQUE_BEHAVIOR__LANGUAGE, [language])
                holder.eSet(LIT.OPAQUE_BEHAVIOR__BODY, [body])
            } else {
                holder.eSet(LIT.OPAQUE_EXPRESSION__LANGUAGE, [language])
                holder.eSet(LIT.OPAQUE_EXPRESSION__BODY, [body])
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to update specification: " + e.getMessage()]
        }

        return [id: elementId, language: language, updated: true]
    }

    @McpTool(name = "modelcode_validation_run", description = '''[VALIDATION] Run specific validation rule Constraints (given by element ID) through the REAL MagicDraw validation engine (DefaultValidationRuleImpl.run) and return their violations (message + annotated target elements). Use this for authoritative verification/retrieval: did the rule actually fail, and on which model elements?

Omit elementIds to validate from the primary model root recursively. To ITERATE/DEBUG a single rule's logic with per-target pass/fail detail (without the full engine), use modelcode_validation_eval instead. To WRITE the rule body first, use modelcode_spec_update.''')
    @McpToolArgument(name = "constraintIds", type = "array", description = "Element IDs of the validation-rule Constraints to execute", required = true)
    @McpToolArgument(name = "elementIds", type = "array", description = "Optional list of target element IDs to validate; omit to validate from the primary model root")
    Map runRules(Map<String, Object> args) {
        def ids = args.get("constraintIds")
        if (!(ids instanceof List) || ((List) ids).isEmpty()) return [error: "constraintIds array is required"]

        def project = getProject()
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

    @McpTool(name = "modelcode_validation_eval", description = '''[VALIDATION] DEBUG a single script-language Constraint: execute its specification DIRECTLY against target elements via GroovyShell, using validation-engine-style bindings (THIS = target element, project, result = holder with set/get). Returns per-target pass/fail with raw values and an errors count. Supports Groovy specs (the rule's return value is the pass/fail).

Use this during AUTHORING/DEBUGGING to iterate on a rule's logic in isolation, without running the full validation UI/engine. For AUTHORITATIVE verification (real engine, violations with messages), use modelcode_validation_run instead. To WRITE the rule body first, use modelcode_spec_update.

Targets: pass explicit targetIds, or omit together with targetType to scan the primary model for elements whose type name contains targetType (default 'Association').''')
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
                if (!(val instanceof Boolean)) entry.raw = String.valueOf(val)
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
