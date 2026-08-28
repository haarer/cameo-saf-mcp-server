import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

import com.nomagic.magicdraw.uml.Finder as Finder
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

class ModelFinder {

    List getModelRoots(def project) {
        def roots = []
        try {
            def models = project.getModels()
            if (models != null) roots.addAll(models)
        } catch (ignored) {}
        if (roots.isEmpty()) {
            def pm = project.getPrimaryModel()
            if (pm != null) roots.add(pm)
        }
        return roots
    }

    Map rootProjectInfo(def project, def root) {
        def primary = null
        try { primary = project.getPrimaryModel() } catch (ignored) {}
        if (root == primary) {
            return [name: project.getName(), primary: true, writable: true]
        }
        def info = [name: "", primary: false, writable: true]
        try {
            def wrapper = com.nomagic.magicdraw.core.ProjectUtilities.getProject(root.eResource())
            if (wrapper != null) {
                info.name = wrapper.getName()
                def desc = null
                try { desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(wrapper) } catch (ignored) {}
                if (desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor) {
                    info.writable = false
                    info.remote = true
                } else if (desc != null) {
                    try {
                        def uri = desc.getURI()
                        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                            def f = new File(uri)
                            if (f.exists() && !f.canWrite()) info.writable = false
                        }
                    } catch (ignored) {}
                }
            }
        } catch (ignored) {}
        if (!info.name) info.name = root.getName() ?: "module"
        return info
    }

    @McpTool(
        name = 'find_elements',
        description = '''Search for model elements by name substring and/or stereotype name and/or type substring. Scans the entire model recursively INCLUDING used projects/modules. Returns id, name, qualifiedName, type, stereotypes, and owning project info.

Use this tool when:
- You need to discover what exists in the model without knowing exact names
- You want a quick overview of elements matching certain criteria

For SAF-enriched results (safKind, safDomain, tagged values), use saf_find_elements_by_type instead.

SAF stereotype naming convention: SAF_<Domain><ViewpointCode>_<ConceptCode> (e.g., SAF_C1_SCXD, SAF_O2_OPFR).
Common prefixes: SAF_ (all SAF stereotypes), SAF_C (conceptual domain), SAF_O (operational domain), SAF_P (physical domain).
All parameters are case-insensitive — don't retry with different casing.
Use spec_list_stereotypes to see all available stereotype names in the model.

Examples:
- name='FFDS', stereotype='SAF_ConceptualContext' → find contexts named FFDS
- stereotype='SAF_ConceptualSystem' → list all conceptual systems
- type='Class', stereotype='SAF_' → find all classes with SAF stereotypes'''')
    @McpToolArgument(
        name = 'name',
        type = 'string',
        description = '''Substring to match against element names (case-insensitive). Leave empty to match all.

Examples: 'FFDS' matches "FFDS Context", "Fire Department FFDS"; 'Fire' matches "Fire Department", "Fire Chief"''')
    @McpToolArgument(
        name = 'stereotype',
        type = 'string',
        description = '''Substring to match against applied stereotype names (case-insensitive). Leave empty to match all.

SAF stereotypes follow the pattern: SAF_<Domain><Viewpoint>_<Concept>
Examples: 
- 'SAF_ConceptualSystem' → exact match for conceptual systems
- 'SAF_C1_' → matches all C1_OSTY viewpoint concepts (SAF_ConceptualContext, SAF_O2_OPFR)
- 'SAF_' → matches any SAF stereotype''')
    @McpToolArgument(
        name = 'type',
        type = 'string',
        description = '''Substring to match against element type name (case-insensitive). Leave empty to match all.

Common SysML types: 'Class', 'Package', 'Activity', 'ProxyPort', 'Interface', 'Connector', 'DataType'
Examples: 'Class' matches all Class instances; 'Package' matches all packages''')
    List findElements(Map<String, Object> args) {
        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [[error: "No model open"]]

        def nameFilter = (args.getOrDefault("name", "") as String).toLowerCase()
        def stereoFilter = (args.getOrDefault("stereotype", "") as String).toLowerCase()
        def typeFilter = (args.getOrDefault("type", "") as String).toLowerCase()

        def results = []
        for (root in getModelRoots(project)) {
            def rootInfo = rootProjectInfo(project, root)
            def fi = Finder.byTypeRecursively()
            def all = fi.find(root, null)

            all.stream()
                .filter { obj -> obj instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement }
                .filter { obj ->
                    def match = true
                    if (match && !nameFilter.isEmpty()) {
                        match = (obj.getName() ?: "").toLowerCase().contains(nameFilter)
                    }
                    if (match && !stereoFilter.isEmpty()) {
                        def stereos = StereotypesHelper.getStereotypes(obj)
                        match = stereos.any { st -> (st.getName() ?: "").toLowerCase().contains(stereoFilter) }
                    }
                    if (match && !typeFilter.isEmpty()) {
                        match = (obj.getClass().getName() ?: "").toLowerCase().contains(typeFilter)
                    }
                    return match
                }
                .forEach { r ->
                    def entry = [
                        id: r.getID(),
                        name: r.getName(),
                        qualifiedName: r.getQualifiedName(),
                        type: r.getClass().getName(),
                        stereotypes: StereotypesHelper.getStereotypes(r).collect { it.getName() },
                        project: rootInfo.name,
                        primary: rootInfo.primary,
                        writable: rootInfo.writable
                    ]
                    refineOwnership(project, r, entry)
                    results.add(entry)
                }
        }
        return results
    }

    void refineOwnership(def project, def elem, Map result) {
        try {
            def ap = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProject(elem)
            if (ap != null) {
                result.project = ap.getName() ?: "module"
                result.primary = false
                boolean ro = false
                try { ro = ap.isReadOnly() } catch (ignored) {}
                result.writable = !ro
            }
        } catch (ignored) {}
    }

    @McpTool(name = "list_owned_elements", description = "List owned elements (direct children) of a parent element by ID, with optional recursive depth. Returns every owned child (named and unnamed), with name (empty for unnamed elements), type (e.g. 'Class', 'Connector End', 'Property', 'Literal Integer'), kind (SAF concept kind where a SAF stereotype is applied, else the type), stereotypes, and ID. Optional filterType restricts results to children whose type/kind matches a case-insensitive substring, e.g. 'Class' for only classes or 'Port' for only ports. Use this before calling get_element_details on individual children to eliminate N+1 drill-down. Works across primary and used-project content.")
    @McpToolArgument(name = "parentId", type = "string", description = "Element ID of the parent element whose owned elements to list", required = true)
    @McpToolArgument(name = "depth", type = "integer", description = "Recursion depth for nested owned elements. 0 = direct children only (default: 0). Use depth=1 to include grandchildren.")
    @McpToolArgument(name = "filterType", type = "string", description = "Optional case-insensitive substring to match against each child's type/kind (e.g. 'Class', 'Connector End', 'Port'). Only matching children are returned; recursion still applies to matching containers.")
    List listOwnedElements(Map<String, Object> args) {
        def parentId = args.get("parentId") as String
        def depth = (args.get("depth") as Integer) ?: 0
        String filterType = args.get("filterType") as String

        if (parentId == null || parentId.isEmpty()) return [[error: "parentId is required"]]

        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [[error: "No model open"]]

        def parent = project.getElementByID(parentId)
        if (parent == null) return [[error: "Parent element not found: " + parentId]]

        def results = []
        collectOwned(parent, results, depth, filterType)
        return results
    }

    // Derive a coarse "kind" for an element: its SAF concept kind when a SAF
    // stereotype is applied, otherwise its human-readable type.
    private String kindOf(def child) {
        def sts = StereotypesHelper.getStereotypes(child)
        for (st in sts) {
            def n = st.getName()
            if (n != null && n.startsWith("SAF_")) return n.substring(4).toLowerCase()
        }
        return child.getHumanType()
    }

    void collectOwned(def elem, List results, int depth, String filterType) {
        if (depth < 0) return
        List children = new ArrayList()
        try { children.addAll(elem.getOwnedElement()) } catch (ignored) {}
        // getOwnedElement() returns every owned child, including unnamed
        // structural elements (ConnectorEnd, Comment, value specifications, ...)
        // that are NOT NamedElements. No name-based filter is applied — all are
        // surfaced, with an empty name when the element has none. (getEnd() is
        // intentionally NOT merged: ConnectorEnds already appear in
        // getOwnedElement(), merging would duplicate them.)
        for (child in children) {
            String childName = ""
            if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) {
                childName = child.getName() ?: ""
            }
            String type = child.getHumanType()
            String kind = kindOf(child)
            if (filterType != null && !filterType.isEmpty()) {
                def hay = (type + "|" + kind).toLowerCase()
                if (!hay.contains(filterType.toLowerCase())) continue
            }

            def entry = [
                id: child.getID(),
                name: childName,
                type: type,
                kind: kind,
                stereotypes: StereotypesHelper.getStereotypes(child).collect { it.getName() },
                parentId: child.getOwner() != null ? child.getOwner().getID() : ""
            ]
            if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment) {
                entry.body = child.getBody() ?: ""
            }

            if (depth > 0) {
                entry.ownedElements = []
                collectOwned(child, entry.ownedElements, depth - 1, filterType)
            }

            results.add(entry)
        }
    }
}
