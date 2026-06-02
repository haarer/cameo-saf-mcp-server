import com.haarer.saf.mcpserver.handlers.McpTool

class ModelQuery {

    @McpTool(name = "find_elements", description = "[GENERIC SYSML] Find elements by name pattern and/or stereotype name using qualifiedName-based search. Returns name, qualifiedName, type, and stereotypes — does NOT include element ID. For element-ID-based lookup, use find_elements_by_type. For SAF-aware search, use saf_find_elements_by_type.")
    List findElements(Map<String, Object> args) {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [[error: "No model open"]]
        def nameFilter = (args.getOrDefault("name", "") as String).toLowerCase()
        def stereoFilter = (args.getOrDefault("stereotype", "") as String).toLowerCase()
        def results = []
        def model = project.getPrimaryModel()
        if (model == null) return [[error: "No primary model"]]
        def topLevel = model.getOwnedElement()
        for (elem in topLevel) {
            addIfMatch(elem, nameFilter, stereoFilter, results)
        }
        return results.collect { r ->
            [name: r.name, qualifiedName: r.qn, type: r.type, stereotypes: r.stereos]
        }
    }

    @McpTool(name = "get_element_info", description = "[GENERIC SYSML] Get detailed element info by qualifiedName (e.g. 'MyModel::MyPackage::MyBlock'). Returns name, qualifiedName, type, stereotypes, owned elements, and relationships. Use this when you know the qualified path but NOT the element ID. To look up by element ID, use get_element_details.")
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

    void addIfMatch(def elem, String nameFilter, String stereoFilter, List results) {
        String name = safeName(elem)
        if (name.isEmpty()) return
        String elemQn = qn(elem)
        String elemType = elem.getClass().getName()
        List elemStereos = stereos(elem)
        boolean nameMatch = nameFilter.isEmpty() || name.toLowerCase().contains(nameFilter)
        boolean stereoMatch = stereoFilter.isEmpty() || elemStereos.any { it.toLowerCase().contains(stereoFilter) }
        if (nameMatch && stereoMatch) {
            results.add([name: name, qn: elemQn, type: elemType, stereos: elemStereos])
        }
        try {
            def children = elem.getOwnedElement()
            for (child in children) {
                addIfMatch(child, nameFilter, stereoFilter, results)
            }
        } catch (ignored) {}
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
