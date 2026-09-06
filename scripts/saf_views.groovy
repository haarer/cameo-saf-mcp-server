import com.haarer.saf.mcpserver.handlers.McpResource
import com.haarer.saf.mcpserver.data.SafDataStore

class SafViews {

    String safeName(def e) {
        try {
            return (e instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) ? (e.getName() ?: "") : ""
        } catch (ignored) {
            return ""
        }
    }

    String qualifiedNameOf(def e) {
        def qn = ""
        try { qn = e.getQualifiedName() ?: "" } catch (ignored) {}
        if (qn == null || qn.isEmpty()) {
            try {
                def parts = []
                def cur = e
                int guard = 0
                while (cur != null && guard++ < 64) {
                    def nm = safeName(cur)
                    if (nm != null && !nm.isEmpty()) parts.add(0, nm)
                    def owner = cur.getOwner()
                    if (owner == null || owner == cur) break
                    cur = owner
                }
                qn = parts.join("::")
            } catch (ignored) {}
        }
        return qn == null ? "" : qn
    }

    List appliedStereotypeNames(def e) {
        def names = []
        try {
            for (s in com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.getStereotypes(e)) {
                def n
                try { n = s.getName() } catch (ignored) { n = null }
                if (n != null && !n.isEmpty() && !names.contains(n)) names.add(n)
            }
        } catch (ignored) {}
        return names
    }

    // Build the stereotype-name -> [viewpoint] map at runtime from the SAF spec index.
    // A "view stereotype" is a SAF profile stereotype applied to a diagram to mark it as
    // a view of a specific viewpoint (named SAF_<VP_ID>, e.g. SAF_C1_SCXD). In the spec
    // index these are recorded as realizeconcept rows whose RealizedConcept is the
    // viewpoint and whose RealizationOfConcept is the view stereotype, so
    // getDirectStereotypesForConcept(viewpointId) yields that viewpoint's view
    // stereotypes. No SAF stereotype names are hardcoded here.
    Map stereoToViewpoints() {
        def map = [:]
        def idx = null
        try {
            def store = SafDataStore.getInstance()
            if (store != null) idx = store.getCurrentIndex()
        } catch (ignored) {}
        if (idx == null) return map
        for (vp in idx.allViewpoints()) {
            def stereos
            try {
                stereos = idx.getDirectStereotypesForConcept(vp.id())
            } catch (ignored) {
                stereos = null
            }
            if (stereos == null) continue
            for (s in stereos) {
                def sName
                try { sName = s.name() } catch (ignored) { sName = null }
                if (sName == null || sName.isEmpty()) continue
                def list = map[sName]
                if (list == null) {
                    list = []
                    map[sName] = list
                }
                def vpId = vp.id()
                if (!list.any { it.id == vpId }) {
                    list.add([
                        id: vpId,
                        name: vp.name(),
                        vpId: vp.vpId(),
                        domain: vp.domain()
                    ])
                }
            }
        }
        return map
    }

    void collectDiagrams(def parent, List results) {
        if (parent == null) return
        try {
            for (child in parent.getOwnedElement()) {
                def ht = ""
                try { ht = child.getHumanType() ?: "" } catch (ignored) {}
                if (ht.contains("Diagram")) {
                    results.add([
                        id: child.getID(),
                        name: safeName(child),
                        type: ht,
                        element: child
                    ])
                }
                collectDiagrams(child, results)
            }
        } catch (ignored) {}
    }

    @McpResource(
        uri = "cameo://saf-views",
        name = "SAF Viewpoint Views",
        description = "Diagrams in the currently-open model that are SAF viewpoint views, detected by the SAF view-stereotypes applied to each diagram element (stereotypes named SAF_<VP_ID>, e.g. SAF_C1_SCXD). For each matching diagram: id, name, qualifiedName, type, stereotypes (the matching view-stereotype names), and viewpoints (the SAF viewpoints those stereotypes realize, each with id, name, vpId, domain). The stereotype-to-viewpoint mapping is built at runtime from the SAF spec index (SafDataStore), so no SAF stereotype names are hardcoded. Returns count 0 and an empty views list when the model has no SAF-annotated diagrams; returns {error: 'No model open'} when no model is open.",
        mimeType = "application/json"
    )
    Map safViews() {
        def project
        try {
            project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        } catch (ignored) {
            project = null
        }
        if (project == null) return [error: "No model open"]

        def projectName = ""
        try { projectName = project.getName() ?: "" } catch (ignored) {}

        def stereoMap = stereoToViewpoints()

        def allDiagrams = []
        def root = null
        try { root = project.getPrimaryModel() } catch (ignored) {}
        if (root != null) collectDiagrams(root, allDiagrams)

        def views = []
        for (dInfo in allDiagrams) {
            def diagram = dInfo.element
            if (diagram == null) continue
            try {
                def applied = appliedStereotypeNames(diagram)
                def matching = applied.findAll { stereoMap.containsKey(it) }
                if (matching.isEmpty()) continue

                def viewpoints = []
                for (m in matching) {
                    for (vp in (stereoMap[m] ?: [])) {
                        if (!viewpoints.any { it.id == vp.id }) viewpoints.add(vp)
                    }
                }

                views.add([
                    id: dInfo.id,
                    name: dInfo.name,
                    qualifiedName: qualifiedNameOf(diagram),
                    type: dInfo.type,
                    stereotypes: matching,
                    viewpoints: viewpoints
                ])
            } catch (ignored) {}
        }

        views.sort { a, b -> (a.name ?: "") <=> (b.name ?: "") }
        return [model: projectName, count: views.size(), views: views]
    }
}
