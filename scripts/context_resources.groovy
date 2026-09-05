import com.haarer.saf.mcpserver.handlers.McpResource

class ContextResources {

    def getProject() {
        return com.nomagic.magicdraw.core.Application.getInstance().getProject()
    }

    String metaclassOf(def e) {
        try {
            if (e instanceof com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement) return "Diagram"
        } catch (ignored) {}
        try {
            def simple = e.getClass().getSimpleName()
            if (simple == null) return ""
            if (simple.endsWith("Impl")) simple = simple.substring(0, simple.length() - 4)
            return simple
        } catch (ignored) {}
        return ""
    }

    Map elementSummary(def e) {
        if (e == null) return null
        try {
            if (e instanceof com.nomagic.magicdraw.uml.core.ModelElementProvider) {
                def el = e.getElement()
                if (el != null) e = el
            }
        } catch (ignored) {}
        def out = [
            id: e.getID(),
            name: (e instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) ? (e.getName() ?: "") : "",
            metaclass: metaclassOf(e),
            type: ""
        ]
        try {
            def t = e.getHumanType()
            if (t != null && !t.isEmpty()) out.type = t
        } catch (ignored) {}
        if ((out.type as String).isEmpty()) out.type = out.metaclass
        def qn = ""
        try { qn = e.getQualifiedName() ?: "" } catch (ignored) {}
        if (qn.isEmpty()) {
            try {
                def parts = []
                def cur = e
                int guard = 0
                while (cur != null && guard++ < 64) {
                    def nm = (cur instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) ? (cur.getName() ?: "") : ""
                    if (nm != null && !nm.isEmpty()) parts.add(0, nm)
                    def owner = cur.getOwner()
                    if (owner == null || owner == cur) break
                    cur = owner
                }
                qn = parts.join("::")
            } catch (ignored) {}
        }
        out.qualifiedName = qn
        try {
            def st = []
            for (s in com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotypes(e)) {
                def n = s.getName()
                if (n != null && !n.isEmpty() && !st.contains(n)) st.add(n)
            }
            if (!st.isEmpty()) out.stereotypes = st
        } catch (ignored) {}
        return out
    }

    Map selectionPayload() {
        def project = getProject()
        if (project == null) return [error: "No model open"]

        def result = [project: [:]]
        try { result.project.name = project.getName() ?: "" } catch (ignored) {}

        def diag = null
        try { diag = project.getActiveDiagram() } catch (ignored) {}
        if (diag != null) {
            def d = [id: diag.getID(), name: "", type: diag.getHumanType()]
            try { d.name = diag.getName() ?: "" } catch (ignored) {}
            result.diagram = d
        }

        def selected = []
        try {
            def sp = com.nomagic.magicdraw.ui.SelectionProvider.getInstance(project)
            if (sp != null) {
                try {
                    for (e in sp.getSelectedElements()) {
                        def s = elementSummary(e)
                        if (s != null && s.size() > 0) selected.add(s)
                    }
                } catch (ignored) {}
            }
        } catch (ignored) {}

        result.selected_elements = selected
        if (selected.isEmpty()) result.empty = true
        return result
    }

    @McpResource(
        uri = "cameo://selection",
        name = "Current Selection",
        description = "Currently selected context in the active Cameo model: selected_elements (the ordered list of currently selected element summaries: id, name, metaclass (structural), type (semantic label MagicDraw resolves from applied stereotypes; equals the metaclass name when unstereotyped), qualifiedName, stereotypes; empty when nothing is selected), the active diagram (id, name, type) if any, and the active project name. Resolves context phrases like 'analyze this model' — 'this' refers to the selected element(s).",
        mimeType = "application/json"
    )
    Map selection() {
        return selectionPayload()
    }
}