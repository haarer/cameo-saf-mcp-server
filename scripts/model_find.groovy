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

    @McpTool(name = "list_owned_elements", description = "List owned elements (direct children) of a parent element by ID, with optional recursive depth. Returns names, types, stereotypes, and IDs so you can decide which elements to drill into. Use this before calling get_element_details on individual children to eliminate N+1 drill-down. Works across primary and used-project content.")
    @McpToolArgument(name = "parentId", type = "string", description = "Element ID of the parent element whose owned elements to list", required = true)
    @McpToolArgument(name = "depth", type = "integer", description = "Recursion depth for nested owned elements. 0 = direct children only (default: 0). Use depth=1 to include grandchildren.")
    List listOwnedElements(Map<String, Object> args) {
        def parentId = args.get("parentId") as String
        def depth = (args.get("depth") as Integer) ?: 0

        if (parentId == null || parentId.isEmpty()) return [[error: "parentId is required"]]

        def project = com.nomagic.magicdraw.core.Application.getInstance().getProject()
        if (project == null) return [[error: "No model open"]]

        def parent = project.getElementByID(parentId)
        if (parent == null) return [[error: "Parent element not found: " + parentId]]

        def results = []
        collectOwned(parent, results, depth)
        return results
    }

    void collectOwned(def elem, List results, int depth) {
        if (depth < 0) return
        try {
            for (child in elem.getOwnedElement()) {
                boolean isNamed = child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement
                // Comments are not NamedElements but carry documentation bodies;
                // include them so they remain discoverable.
                boolean isComment = child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Comment
                if (!isNamed && !isComment) continue

                def entry = [
                    id: child.getID(),
                    name: isNamed ? (child.getName() ?: "") : "",
                    type: child.getHumanType(),
                    stereotypes: StereotypesHelper.getStereotypes(child).collect { it.getName() },
                    parentId: child.getOwner() != null ? child.getOwner().getID() : ""
                ]
                if (isComment) entry.body = child.getBody() ?: ""

                if (isNamed && depth > 0) {
                    entry.ownedElements = []
                    collectOwned(child, entry.ownedElements, depth - 1)
                }

                results.add(entry)
            }
        } catch (ignored) {}
    }
}
