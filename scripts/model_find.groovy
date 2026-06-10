import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

import com.nomagic.magicdraw.uml.Finder as Finder
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

class ModelFinder {

    @McpTool(
        name = 'find_elements',
        description = '''Search for model elements by name substring and/or stereotype name and/or type substring. Scans the entire model recursively. Returns name, qualifiedName, type, and stereotypes (no element IDs).

Use this tool when:
- You need to discover what exists in the model without knowing exact names
- You want a quick overview of elements matching certain criteria
- You don't need element IDs for further operations

For ID-based queries that return element IDs for use with other tools, use find_elements_by_type or saf_find_elements_by_type.

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
- 'SAF_C1_' → matches all C1_OSTY viewpoint concepts (SAF_ConceptualContext, SAF_Mission, etc.)
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

        def model = project.getPrimaryModel()
        if (model == null) return [[error: "No primary model"]]

        def fi = Finder.byTypeRecursively()
        def all = fi.find(model, null)

        def results = all.stream()
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
            .toList()

        return results.collect { r ->
            [
                name: r.getName(),
                qualifiedName: r.getQualifiedName(),
                type: r.getClass().getName(),
                stereotypes: StereotypesHelper.getStereotypes(r).collect { it.getName() }
            ]
        }
    }

    @McpTool(name = "list_owned_elements", description = "List owned elements (direct children) of a parent element by ID, with optional recursive depth. Returns names, types, stereotypes, and IDs so you can decide which elements to drill into. Use this before calling get_element_details on individual children to eliminate N+1 drill-down.")
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
                if (child instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) {
                    def stereos = StereotypesHelper.getStereotypes(child).collect { it.getName() }

                    def entry = [
                        id: child.getID(),
                        name: child.getName() ?: "",
                        type: child.getHumanType(),
                        stereotypes: stereos,
                        parentId: child.getOwner() != null ? child.getOwner().getID() : ""
                    ]

                    if (depth > 0) {
                        entry.ownedElements = []
                        collectOwned(child, entry.ownedElements, depth - 1)
                    }

                    results.add(entry)
                }
            }
        } catch (ignored) {}
    }
}
