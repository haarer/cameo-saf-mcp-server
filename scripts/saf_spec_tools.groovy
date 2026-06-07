import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.haarer.saf.mcpserver.data.SafDataStore

class SafSpecTools {

    def getIndex() {
        def store = SafDataStore.getInstance()
        def idx = store.getCurrentIndex()
        if (idx == null) throw new RuntimeException("SAF data not loaded. Check that _data/ is configured correctly.")
        return idx
    }

    // ---- List tools -------------------------------------------------------

    @McpTool(name = "spec_list_viewpoints", description = "SAF Spec: List all viewpoints, optionally filtered by domain, aspect, or maturity. Use this to discover what viewpoints are available and their grid position (domain x aspect).")
    @McpToolArgument(name = "domain", type = "string", description = "Filter by domain (e.g. architecture_management, operational, conceptual, physical)")
    @McpToolArgument(name = "aspect", type = "string", description = "Filter by aspect (e.g. taxonomy___structure, context___exchange, process___behavior)")
    @McpToolArgument(name = "maturity", type = "string", description = "Filter by maturity (released, proposed, under construction)")
    List specListViewpoints(Map<String, Object> args) {
        def domainFilter = (args.get("domain") ?: "") as String
        def aspectFilter = (args.get("aspect") ?: "") as String
        def maturityFilter = (args.get("maturity") ?: "") as String

        def result = []
        for (vp in getIndex().allViewpoints()) {
            if (domainFilter && !vp.domain().contains(domainFilter.toLowerCase().replaceAll(" ", "_"))) continue
            if (aspectFilter && !vp.aspect().toLowerCase().contains(aspectFilter.toLowerCase())) continue
            if (maturityFilter && !vp.maturity().toLowerCase().contains(maturityFilter.toLowerCase())) continue
            result.add([
                id: vp.id(),
                name: vp.name(),
                vpId: vp.vpId(),
                domain: vp.domain(),
                aspect: vp.aspect(),
                maturity: vp.maturity(),
                exposure: vp.exposure()
            ])
        }
        result.sort { a, b -> a.name <=> b.name }
        return result
    }

    @McpTool(name = "spec_list_concepts", description = "SAF Spec: List all SAF concepts with their type (Class, Activity, etc.) and ID. Use this to discover concept names for drill-down with spec_get_concept.")
    List specListConcepts(Map<String, Object> args) {
        def result = []
        for (c in getIndex().allConcepts()) {
            result.add([id: c.id(), name: c.name(), type: c.classType()])
        }
        result.sort { a, b -> a.name <=> b.name }
        return result
    }

    @McpTool(name = "spec_list_concerns", description = "SAF Spec: List all concerns (engineering questions) with their category. Concerns represent information needs of stakeholders.")
    List specListConcerns(Map<String, Object> args) {
        def result = []
        for (c in getIndex().allConcerns()) {
            result.add([id: c.id(), name: c.name(), category: c.category(), question: c.question()])
        }
        result.sort { a, b -> a.name <=> b.name }
        return result
    }

    @McpTool(name = "spec_list_stakeholders", description = "SAF Spec: List all SAF stakeholders with their name and ID. Stakeholders have concerns that viewpoints address.")
    List specListStakeholders(Map<String, Object> args) {
        def result = []
        for (s in getIndex().allStakeholders()) {
            result.add([id: s.id(), name: s.name()])
        }
        result.sort { a, b -> a.name <=> b.name }
        return result
    }

    @McpTool(name = "spec_list_stereotypes", description = "SAF Spec: List all SAF stereotypes with their name, ID, and realized concepts. Stereotypes are the model-level implementation of SAF concepts.")
    List specListStereotypes(Map<String, Object> args) {
        def idx = getIndex()
        def result = []
        for (s in idx.allStereotypes()) {
            def concepts = idx.getConceptsForStereotype(s.id()).collect { it.name() }
            result.add([
                id: s.id(),
                name: s.name(),
                realizedConcepts: concepts
            ])
        }
        result.sort { a, b -> a.name <=> b.name }
        return result
    }

    // ---- Search tool ------------------------------------------------------

    @McpTool(name = "spec_search", description = "SAF Spec: Search across all SAF entities (viewpoints, concepts, concerns, stakeholders, stereotypes) by name substring. Returns matches from all types sorted by relevance. Use this for discovery when you don't know the exact name.")
    @McpToolArgument(name = "query", type = "string", description = "Search term (case-insensitive substring match)", required = true)
    @McpToolArgument(name = "type", type = "string", description = "Limit search to a specific type: viewpoint, concept, concern, stakeholder, stereotype. Omit to search all.")
    List specSearch(Map<String, Object> args) {
        def query = args.get("query") as String
        def typeFilter = args.get("type") as String
        if (query == null || query.isEmpty()) return [error: "query is required"]
        return getIndex().search(query, typeFilter)
    }

    // ---- Get tools --------------------------------------------------------

    @McpTool(name = "spec_get_viewpoint", description = "SAF Spec: Get detailed info about a single SAF viewpoint by name, VP_ID (e.g. O1_OSTY), or ID. Returns metadata, framed concerns, exposed concept names, and viewpoint dependencies.")
    @McpToolArgument(name = "name", type = "string", description = "Viewpoint name, VP_ID, or ID", required = true)
    Map specGetViewpoint(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()
        def vp = idx.getViewpoint(name)
        if (vp == null) return [error: "Viewpoint not found: " + name]

        // Resolve concerns
        def concerns = idx.getConcernsForViewpoint(vp.id()).collect { c ->
            [id: c.id(), question: c.question(), category: c.category()]
        }

        // Resolve exposed concept names
        def concepts = idx.getConceptsForViewpoint(vp.id()).collect { c ->
            [id: c.id(), name: c.name(), type: c.classType()]
        }

        // Resolve recommended/required viewpoints
        def recommended = vp.recommendedVpIds().findResults { vid ->
            def rvp = idx.getViewpointById(vid)
            rvp != null ? [id: rvp.id(), name: rvp.name(), vpId: rvp.vpId()] : null
        }
        def required = vp.requiredVpIds().findResults { vid ->
            def rvp = idx.getViewpointById(vid)
            rvp != null ? [id: rvp.id(), name: rvp.name(), vpId: rvp.vpId()] : null
        }

        // Resolve stakeholders
        def stakeholders = vp.stakeholderIds().findResults { sid ->
            def s = idx.getStakeholderById(sid)
            s != null ? [id: s.id(), name: s.name()] : null
        }

        return [
            id: vp.id(),
            name: vp.name(),
            vpId: vp.vpId(),
            domain: vp.domain(),
            aspect: vp.aspect(),
            maturity: vp.maturity(),
            exposure: vp.exposure(),
            purpose: vp.purpose(),
            applicability: vp.applicability(),
            presentation: vp.presentation(),
            stakeholders: stakeholders,
            concerns: concerns,
            exposedConcepts: concepts,
            recommendedViewpoints: recommended,
            requiredViewpoints: required
        ]
    }

    @McpTool(name = "spec_get_viewpoint_concepts", description = "SAF Spec: Get all concepts exposed by a viewpoint, fully resolved with their inheritance hierarchy, relationships, association ends, and documentation. Use this to understand the data structure a viewpoint makes available.")
    @McpToolArgument(name = "viewpoint_name", type = "string", description = "Viewpoint name, VP_ID, or ID", required = true)
    Map specGetViewpointConcepts(Map<String, Object> args) {
        def vpName = args.get("viewpoint_name") as String
        if (vpName == null || vpName.isEmpty()) return [error: "viewpoint_name is required"]

        def idx = getIndex()
        def vp = idx.getViewpoint(vpName)
        if (vp == null) return [error: "Viewpoint not found: " + vpName]

        def concepts = idx.getConceptsForViewpoint(vp.id()).collect { c -> buildConceptDetail(idx, c) }

        return [
            viewpoint: [id: vp.id(), name: vp.name(), vpId: vp.vpId()],
            concepts: concepts,
            totalConcepts: concepts.size()
        ]
    }

    @McpTool(name = "spec_get_viewpoint_concerns", description = "SAF Spec: Get all concerns framed by a viewpoint, with stakeholder rationales explaining why each stakeholder cares about each concern. Use this to understand the information needs a viewpoint must satisfy.")
    @McpToolArgument(name = "viewpoint_name", type = "string", description = "Viewpoint name, VP_ID, or ID", required = true)
    Map specGetViewpointConcerns(Map<String, Object> args) {
        def vpName = args.get("viewpoint_name") as String
        if (vpName == null || vpName.isEmpty()) return [error: "viewpoint_name is required"]

        def idx = getIndex()
        def vp = idx.getViewpoint(vpName)
        if (vp == null) return [error: "Viewpoint not found: " + vpName]

        def concerns = idx.getConcernsForViewpoint(vp.id()).collect { c ->
            def stakeholders = idx.getStakeholdersForConcern(c.id()).collect { s ->
                def rationales = idx.getRationalesForConcern(c.id()).findAll { r ->
                    r.stakeholderId() == s.id()
                }.collect { r -> r.documentation() }
                [id: s.id(), name: s.name(), rationales: rationales]
            }
            [id: c.id(), question: c.question(), category: c.category(), stakeholders: stakeholders]
        }

        return [
            viewpoint: [id: vp.id(), name: vp.name(), vpId: vp.vpId()],
            concerns: concerns,
            totalConcerns: concerns.size()
        ]
    }

    @McpTool(name = "spec_get_concept", description = "SAF Spec: Get a single SAF concept with its full neighborhood — inheritance hierarchy (parents and children), relationships (association ends with multiplicities), which viewpoints expose it, and which stereotypes realize it.")
    @McpToolArgument(name = "name", type = "string", description = "Concept name or ID", required = true)
    Map specGetConcept(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()
        def concept = idx.getConcept(name)
        if (concept == null) return [error: "Concept not found: " + name]

        return buildConceptDetail(idx, concept)
    }

    @McpTool(name = "spec_get_concept_stereotypes", description = "SAF Spec: Get all stereotypes that realize a given SAF concept — including both direct realizations (stereotype explicitly assigned to this concept) and indirect UML metaclass mappings (via SCM_TypedBy, SCM_ContainedIn, SCM_Attribute). Use this to trace from a concept to its implementation stereotypes.")
    @McpToolArgument(name = "name", type = "string", description = "Concept name or ID", required = true)
    Map specGetConceptStereotypes(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()
        def concept = idx.getConcept(name)
        if (concept == null) return [error: "Concept not found: " + name]

        def direct = idx.getDirectStereotypesForConcept(concept.id()).collect { s ->
            [stereotypeId: s.id(), stereotypeName: s.name(), documentation: s.documentation(), kind: "direct"]
        }
        def indirect = idx.getIndirectStereotypesForConcept(concept.id()).collect { s ->
            [stereotypeId: s.id(), stereotypeName: s.name(), documentation: s.documentation(), kind: "indirect_via_metaclass"]
        }

        return [
            concept: [id: concept.id(), name: concept.name(), classType: concept.classType()],
            stereotypes: direct + indirect,
            totalDirect: direct.size(),
            totalIndirect: indirect.size()
        ]
    }

    @McpTool(name = "spec_get_concern", description = "SAF Spec: Get a concern's details — the engineering question it frames, its category/owner, and which viewpoints address this concern. Use this to understand stakeholder information needs.")
    @McpToolArgument(name = "name", type = "string", description = "Concern ID or name text", required = true)
    Map specGetConcern(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()

        // Try by ID first, then search by text substring
        def concern = idx.getConcernById(name)
        if (concern == null) {
            for (c in idx.allConcerns()) {
                if (c.question().toLowerCase().contains(name.toLowerCase()) || c.name().toLowerCase().contains(name.toLowerCase())) {
                    concern = c
                    break
                }
            }
        }
        if (concern == null) return [error: "Concern not found: " + name]

        def viewpoints = idx.getViewpointsForConcern(concern.id()).collect { vp ->
            [id: vp.id(), name: vp.name(), vpId: vp.vpId(), domain: vp.domain(), aspect: vp.aspect()]
        }

        def stakeholders = idx.getStakeholdersForConcern(concern.id()).collect { s ->
            def rationales = idx.getRationalesForConcern(concern.id()).findAll { r ->
                r.stakeholderId() == s.id()
            }.collect { r -> r.documentation() }
            [id: s.id(), name: s.name(), rationales: rationales]
        }

        return [
            id: concern.id(),
            question: concern.question(),
            category: concern.category(),
            viewpoints: viewpoints,
            stakeholders: stakeholders
        ]
    }

    @McpTool(name = "spec_get_stakeholder", description = "SAF Spec: Get a stakeholder's full profile with documentation, all their concerns with rationales explaining why they care. Use this to understand a stakeholder's information needs.")
    @McpToolArgument(name = "name", type = "string", description = "Stakeholder name (e.g. System Architect)", required = true)
    Map specGetStakeholder(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()
        def stakeholder = idx.getStakeholderByName(name)
        if (stakeholder == null) return [error: "Stakeholder not found: " + name + ". Use spec_list_stakeholders to see available stakeholders."]

        def concerns = idx.getConcernsForStakeholder(stakeholder.id()).collect { c ->
            def rationales = idx.getRationalesForStakeholder(stakeholder.id()).findAll { r ->
                r.concernId() == c.id()
            }.collect { r -> r.documentation() }
            [id: c.id(), question: c.question(), category: c.category(), rationales: rationales]
        }

        return [
            id: stakeholder.id(),
            name: stakeholder.name(),
            documentation: stakeholder.documentation(),
            concerns: concerns
        ]
    }

    @McpTool(name = "spec_get_stereotype", description = "SAF Spec: Get a stereotype's details — its documentation, which SAF concepts it realizes (direct), and which special implementations (SCM_TypedBy, SCM_ContainedIn, SCM_Attribute) involve it. Use this to trace from a stereotype to its conceptual meaning.")
    @McpToolArgument(name = "name", type = "string", description = "Stereotype name (e.g. SAF_ConceptualSystem)", required = true)
    Map specGetStereotype(Map<String, Object> args) {
        def name = args.get("name") as String
        if (name == null || name.isEmpty()) return [error: "name is required"]

        def idx = getIndex()
        def stereo = idx.getStereotypeByName(name)
        if (stereo == null) return [error: "Stereotype not found: " + name + ". Use spec_list_stereotypes to see available stereotypes."]

        def realizedConcepts = idx.getConceptsForStereotype(stereo.id()).collect { c ->
            [id: c.id(), name: c.name(), classType: c.classType()]
        }

        def specialImpls = idx.getSpecialImplementationsForStereotype(stereo.name()).collect { si ->
            [
                id: si.id(),
                relationType: si.relationType(),
                clientElement: si.clientName(),
                supplierElement: si.supplierName()
            ]
        }

        return [
            id: stereo.id(),
            name: stereo.name(),
            documentation: stereo.documentation(),
            realizedConcepts: realizedConcepts,
            specialImplementations: specialImpls
        ]
    }

    @McpTool(name = "spec_get_special_implementations", description = "SAF Spec: Get special implementation relations (SCM_TypedBy, SCM_ContainedIn, SCM_Attribute) that link UML/SysML metaclasses to SAF stereotypes. Use this to understand how UML is mapped to SAF concepts. Optionally filter by a stereotype name.")
    @McpToolArgument(name = "stereotype_name", type = "string", description = "Optional: filter to show only implementations involving this stereotype")
    List specGetSpecialImplementations(Map<String, Object> args) {
        def stereoFilter = args.get("stereotype_name") as String

        def idx = getIndex()
        def allImpls = idx.allSpecialImplementations()

        if (stereoFilter != null && !stereoFilter.isEmpty()) {
            allImpls = allImpls.findAll { si ->
                si.supplierName().toLowerCase().contains(stereoFilter.toLowerCase()) ||
                si.clientName().toLowerCase().contains(stereoFilter.toLowerCase())
            }
        }

        return allImpls.collect { si ->
            [
                id: si.id(),
                relationType: si.relationType(),
                typedElement: si.clientName(),
                typeDefinition: si.supplierName()
            ]
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private Map buildConceptDetail(def idx, def concept) {
        // Inheritance
        def parents = idx.getConceptParents(concept.id()).collect { p ->
            [id: p.id(), name: p.name(), type: p.classType()]
        }
        def children = idx.getConceptChildren(concept.id()).collect { c ->
            [id: c.id(), name: c.name(), type: c.classType()]
        }

        // Association ends
        def assocEnds = concept.associationEnds().collect { ae ->
            [
                relatedConcept: [id: ae.conceptId(), name: ae.conceptName()],
                multiplicity: ae.multiplicity()
            ]
        }

        // Viewpoints that expose this concept
        def viewpoints = idx.getViewpointsForConcept(concept.id()).collect { vp ->
            [id: vp.id(), name: vp.name(), vpId: vp.vpId(), domain: vp.domain(), aspect: vp.aspect()]
        }

        // Stereotypes
        def directStereos = idx.getDirectStereotypesForConcept(concept.id()).collect { s ->
            [id: s.id(), name: s.name(), kind: "direct"]
        }
        def indirectStereos = idx.getIndirectStereotypesForConcept(concept.id()).collect { s ->
            [id: s.id(), name: s.name(), kind: "indirect_via_metaclass"]
        }

        return [
            id: concept.id(),
            name: concept.name(),
            classType: concept.classType(),
            documentation: concept.documentation(),
            parents: parents,
            children: children,
            associationEnds: assocEnds,
            viewpoints: viewpoints,
            stereotypes: directStereos + indirectStereos,
            inViewpointIds: concept.inViewpointIds()
        ]
    }
}
