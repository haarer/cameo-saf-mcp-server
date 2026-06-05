import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument

import com.nomagic.magicdraw.uml.Finder as Finder
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

class ModelFinder {

    @McpTool(
        name = 'find_elements',
        description = 'Search for model elements by name substring and/or stereotype name and/or type substring. Scans the entire model recursively. Returns name, qualifiedName, type, and stereotypes (no element IDs). For ID-based queries use find_elements_by_type. For SAF-enriched search use saf_find_elements_by_type.')
    @McpToolArgument(
        name = 'name',
        type = 'string',
        description = 'Substring to match against element names (case-insensitive). Leave empty to match all.')
    @McpToolArgument(
        name = 'stereotype',
        type = 'string',
        description = 'Substring to match against applied stereotype names (case-insensitive). Leave empty to match all.')
    @McpToolArgument(
        name = 'type',
        type = 'string',
        description = 'Substring to match against element type name (case-insensitive). Leave empty to match all.')
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

}
