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
                if (prop.getClass().getName() != "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property") continue
                def propType = prop.getType()
                if (propType != null) {
                    def propStereos = stereos(prop)
                    rels.add([type: "Property", direction: "ref", name: safeName(prop), target: safeName(propType), targetQualifiedName: qn(propType), stereotypes: propStereos])
                }
            }
        } catch (ignored) {}
        return [name: name, qualifiedName: elemQn, type: elemType, stereotypes: elemStereos, ownedElements: owned, relationships: rels]
    }

    @McpTool(name = "list_model_stereotypes", description = "Return all stereotype names currently applied in the open model, grouped by prefix (SAF_, HyperlinkOwner, etc.). Use this to discover valid stereotype names before searching or creating elements.")
    Map listModelStereotypes() {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]

        def allStereos = [:] as LinkedHashMap
        collectAppliedStereos(project.getPrimaryModel(), allStereos)

        def grouped = [:] as LinkedHashMap
        allStereos.each { name, count ->
            def prefix = name.contains("_") ? name.substring(0, name.indexOf("_")) : "(no prefix)"
            if (!grouped.containsKey(prefix)) {
                grouped[prefix] = []
            }
            grouped[prefix] << [name: name, count: count]
        }
        grouped.each { k, v -> v.sort { it.name } }

        return [
            total: allStereos.size(),
            groups: grouped.keySet().sort().collect { prefix ->
                [prefix: prefix, stereotypes: grouped[prefix]]
            }
        ]
    }

    void collectAppliedStereos(def elem, Map acc) {
        try {
            def stereos = elem.getAppliedStereotype()
            for (st in stereos) {
                def name = st.getName()
                if (name != null && !name.isEmpty()) {
                    acc[name] = (acc[name] ?: 0) + 1
                }
            }
            for (child in elem.getOwnedElement()) {
                collectAppliedStereos(child, acc)
            }
        } catch (ignored) {}
    }

    @McpTool(name = "get_port_type_info", description = "Get the interface definition that types a proxy port. Returns the port name, the interface definition (name, ID, stereotypes, operations), and the connectors the port participates in. Use this to discover which interface connects which context elements.")
    @McpToolArgument(name = "portId", type = "string", description = "Element ID of the proxy port to inspect", required = true)
    Map getPortTypeInfo(Map<String, Object> args) {
        def portId = args.get("portId") as String
        if (portId == null || portId.isEmpty()) return [error: "portId is required"]

        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [error: "No model open"]

        def port = project.getElementByID(portId)
        if (port == null) return [error: "Port not found: " + portId]

        def portName = safeName(port)
        def portType = port.getClass().getName()

        // Get the type (interface) of the port
        def iface = null
        try {
            iface = port.getType()
        } catch (ignored) {}

        def ifaceInfo = null
        if (iface != null) {
            def ifaceStereos = stereos(iface)
            def operations = []
            try {
                for (owned in iface.getOwnedElement()) {
                    if (owned.getClass().getName() == "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Operation") {
                        operations.add([
                            id: owned.getID(),
                            name: safeName(owned)
                        ])
                    }
                }
            } catch (ignored) {}

            ifaceInfo = [
                id: iface.getID(),
                name: safeName(iface),
                type: iface.getClass().getName(),
                stereotypes: ifaceStereos,
                operations: operations
            ]
        }

        // Find connectors this port participates in
        def connectors = []
        try {
            def owner = port.getOwner()
            if (owner != null) {
                for (owned in owner.getOwnedElement()) {
                    if (owned.getClass().getName() == "com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Connector") {
                        def connEnds = owned.getEnd()
                        for (end in connEnds) {
                            def role = end.getRole()
                            if (role != null && role.getID() == portId) {
                                def otherEnd = connEnds.find { e ->
                                    def r = e.getRole()
                                    r != null && r.getID() != portId
                                }
                                def otherPort = otherEnd != null ? otherEnd.getRole() : null
                                connectors.add([
                                    connectorId: owned.getID(),
                                    connectorName: safeName(owned),
                                    otherPortId: otherPort != null ? otherPort.getID() : "",
                                    otherPortName: otherPort != null ? safeName(otherPort) : ""
                                ])
                                break
                            }
                        }
                    }
                }
            }
        } catch (ignored) {}

        return [
            portId: portId,
            portName: portName,
            portType: portType,
            interface: ifaceInfo,
            connectors: connectors
        ]
    }
}
