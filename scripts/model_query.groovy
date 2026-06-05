import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

class ModelQuery {

    @McpTool(name = "get_element_info", description = "Get detailed info about a model element by its qualified name (e.g. 'MyModel::MyPackage::MyBlock'). Returns name, qualifiedName, type, stereotypes, owned elements, and relationships (dependencies, generalizations, properties). Use this when you know the element's qualified path. For lookup by element ID, use get_element_details.")
    @McpToolArgument(name = "qualifiedName", type = "string", description = "Fully qualified name of the element (e.g. 'Model::Package::Element')", required = true)
    Map getElementInfo(Map<String, Object> args) {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]
        def qn = args.getOrDefault("qualifiedName", "") as String
        if (qn.isEmpty()) return [error: "qualifiedName is required"]
        def elem = findByQName(project.getPrimaryModel(), qn)
        if (elem == null) return [error: "Element not found: " + qn]
        return buildMap(elem, 1)
    }

    // --- helpers ---

    String qn(def elem) {
        try { return elem.getQualifiedName() ?: "" } catch (ignored) { return "" }
    }

    String safeName(def elem) {
        try { return elem.getName() ?: "" } catch (ignored) { return "" }
    }

    List<String> stereos(def elem) {
        def names = []
        try {
            for (st in elem.getAppliedStereotype()) {
                def n = st.getName()
                if (n != null && !n.isEmpty()) names.add(n)
            }
        } catch (ignored) {}
        return names
    }

    def findByQName(def elem, String targetQn) {
        if (targetQn.isEmpty()) return null
        if (qn(elem) == targetQn) return elem
        try {
            def children = elem.getOwnedElement()
            for (child in children) {
                def found = findByQName(child, targetQn)
                if (found != null) return found
            }
        } catch (ignored) {}
        return null
    }

    Map buildMap(def elem, int depth) {
        String name = safeName(elem)
        String elemQn = qn(elem)
        String elemType = elem.getClass().getName()
        List elemStereos = stereos(elem)
        def owned = []
        if (depth > 0) {
            try {
                def children = elem.getOwnedElement()
                for (child in children) {
                    owned.add(buildMap(child, depth - 1))
                }
            } catch (ignored) {}
        }
        def rels = []
        try {
            for (dep in elem.getClientDependency()) {
                def depStereos = stereos(dep)
                for (supplier in dep.getSupplier()) {
                    rels.add([type: dep.getClass().getName(), direction: "out", target: safeName(supplier), targetQualifiedName: qn(supplier), stereotypes: depStereos])
                }
            }
        } catch (ignored) {}
        try {
            def children = elem.getOwnedElement()
            for (child in children) {
                try {
                    for (dep in child.getClientDependency()) {
                        if (dep.getSupplier().contains(elem)) {
                            def depStereos = stereos(dep)
                            rels.add([type: dep.getClass().getName(), direction: "in", source: safeName(child), sourceQualifiedName: qn(child), stereotypes: depStereos])
                        }
                    }
                } catch (ignored) {}
            }
        } catch (ignored) {}
        try {
            for (gen in elem.getGeneralization()) {
                def general = gen.getGeneral()
                if (general != null) {
                    rels.add([type: "Generalization", direction: "general", target: safeName(general), targetQualifiedName: qn(general), stereotypes: []])
                }
            }
        } catch (ignored) {}
        try {
            for (prop in elem.getOwnedElement()) {
                if (!(prop instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property)) continue
                def propType = prop.getType()
                if (propType != null) {
                    def propStereos = stereos(prop)
                    rels.add([type: "Property", direction: "ref", name: safeName(prop), target: safeName(propType), targetQualifiedName: qn(propType), stereotypes: propStereos])
                }
            }
        } catch (ignored) {}
        return [name: name, qualifiedName: elemQn, type: elemType, stereotypes: elemStereos, ownedElements: owned, relationships: rels]
    }
}
