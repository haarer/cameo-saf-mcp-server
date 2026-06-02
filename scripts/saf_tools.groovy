import com.haarer.saf.mcpserver.handlers.McpTool
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.impl.ElementsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.*
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype

class SafTools {

    def getProject() {
        def proj = Application.getInstance().getProject()
        if (proj == null) throw new RuntimeException("No model open")
        return proj
    }

    def getFactory() {
        return getProject().getElementsFactory()
    }

    def resolveElement(String id) {
        if (id == null || id.isEmpty()) return null
        return getProject().getElementByID(id)
    }

    def findStereotype(String name) {
        if (name == null || name.isEmpty()) return null
        def project = getProject()
        try {
            def all = StereotypesHelper.getAllStereotypes(project)
            if (all != null) {
                for (st in all) {
                    if (st.getName() == name) return st
                }
            }
            // Fallback: case-insensitive search
            if (all != null) {
                for (st in all) {
                    if (st.getName() != null && st.getName().equalsIgnoreCase(name)) return st
                }
            }
        } catch (Exception e) {
            // ignore, return null
        }
        return null
    }

    // SAF concept → [SysML type, stereotype, profile]
    static final CONCEPT_MAP = [
        "system_requirement":            ["Class",     "SAF_SystemRequirement"],
        "conceptual_system":             ["Class",     "SAF_ConceptualSystem"],
        "system_function":               ["Activity",  "SAF_Function"],
        "conceptual_function":           ["Activity",  "SAF_Function"],
        "conceptual_function_action":    ["CallBehaviorAction", "SAF_FunctionAction"],
        "system_process":                ["Activity",  "SAF_SystemProcess"],
        "conceptual_interface":          ["Class",     "SAF_ConceptualInterfaceDefinition"],
        "physical_system":               ["Class",     "SAF_PhysicalSystem"],
        "physical_product":              ["Class",     "SAF_PhysicalSystem"],
        "operational_performer":         ["Class",     "SAF_OperationalPerformer"],
        "operational_capability":        ["Class",     "SAF_OperationalCapability"],
        "system_capability":             ["Class",     "SAF_SystemCapability"],
        "operational_story":             ["Class",     "SAF_OperationalStory"],
        "operational_process":           ["Activity",  "SAF_OperationalProcess"],
        "operational_activity":          ["Activity",  "SAF_OperationalProcess"],
        "stakeholder":                   ["Class",     "SAF_Stakeholder"],
        "concern":                       ["Comment",   "SAF_SystemOfInterestConcern"],
        "mission":                       ["Class",     null],
        "requirement":                   ["Class",     "SAF_SystemRequirement"],
        "proxy_port":                    ["ProxyPort", null],
        "port":                          ["Port",      null],
        "connector":                     ["Connector", null],
        "exchange_type":                 ["ValueType", null],
        "activity_partition":            ["ActivityPartition", null],
        "comment":                       ["Comment",   null],
    ]

    // Relationship types that are SAF-aware
    static final SAF_RELATIONSHIP_TYPES = [
        "satisfy":   ["abstraction", "Satisfy"],
        "derive":    ["abstraction", "DeriveReqt"],
        "trace":     ["abstraction", "Trace"],
        "refine":    ["abstraction", "Refine"],
        "verify":    ["abstraction", "Verify"],
        "allocate":  ["dependency",  "allocate"],
        "composition": ["composition", null],
        "aggregation": ["association", null],
        "dependency":  ["dependency",  null],
        "generalization": ["generalization", null],
        "controlflow":   ["controlflow",    null],
        "objectflow":    ["objectflow",     null],
        "connector":     ["connector",      null],
        "association":   ["association",    null],
    ]

    @McpTool(name = "saf_create_element", description = "[SAF] Create an element using SAF concept kinds (system_requirement, conceptual_system, physical_system, operational_performer, etc.). Maps the kind to the correct SysML type AND applies the appropriate SAF stereotype. For raw SysML types without SAF, use create_element instead. Returns the created element ID.")
    Map safCreateElement(Map<String, Object> args) {
        def kind = (args.get("kind") ?: "") as String
        def name = args.get("name") as String
        def parentId = args.get("parentId") as String
        def documentation = args.get("documentation") as String

        if (kind.isEmpty()) return [error: "kind is required (e.g. system_requirement, conceptual_system, physical_system)"]
        if (name == null || name.isEmpty()) return [error: "name is required"]
        if (parentId == null || parentId.isEmpty()) return [error: "parentId is required"]

        def mapping = CONCEPT_MAP[kind.toLowerCase()]
        if (mapping == null) {
            def valid = CONCEPT_MAP.keySet().sort().join(", ")
            return [error: "Unknown SAF kind: " + kind + ". Valid kinds: " + valid]
        }

        def sysmlType = mapping[0] as String
        def stereotypeName = mapping[1] as String

        def project = getProject()
        def parent = resolveElement(parentId)
        if (parent == null) return [error: "Parent element not found: " + parentId]

        def created = null
        def sm = SessionManager.getInstance()
        sm.createSession(project, "saf_create_element")
        try {
            created = createByType(sysmlType)
            if (created instanceof NamedElement) {
                ((NamedElement) created).setName(name)
            }
            ModelElementsManager.getInstance().addElement(created, parent)

            if (stereotypeName != null && !stereotypeName.isEmpty()) {
                def st = findStereotype(stereotypeName)
                if (st != null) {
                    StereotypesHelper.addStereotype(created, st)
                } else {
                    sm.cancelSession(project)
                    return [error: "SAF stereotype not found in model: " + stereotypeName + ". Ensure the SAF profile is applied to the model."]
                }
            }

            if (documentation != null && !documentation.isEmpty()) {
                def comment = getFactory().createCommentInstance()
                comment.setBody(documentation)
                ModelElementsManager.getInstance().addElement(comment, created)
            }

            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        return [
            id: created.getID(),
            name: name,
            kind: kind,
            sysmlType: sysmlType,
            stereotype: stereotypeName,
            parentId: parentId
        ]
    }

    @McpTool(name = "saf_set_requirement_tags", description = "[SAF] Set 'id' and 'text' tagged values on a SAF_SystemRequirement element. For setting arbitrary tagged values on any stereotyped element, use set_tagged_values instead.")
    Map safSetRequirementTags(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def reqId = args.get("reqId") as String
        def text = args.get("text") as String

        if (elementId == null || elementId.isEmpty()) return [error: "elementId is required"]
        if (reqId == null || reqId.isEmpty()) return [error: "reqId is required"]
        if (text == null || text.isEmpty()) return [error: "text is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def stereo = findStereotype("SAF_SystemRequirement")
        if (stereo == null) return [error: "SAF_SystemRequirement stereotype not found. Is the SAF profile applied?"]

        if (!StereotypesHelper.hasStereotype(element, stereo)) {
            def sm0 = SessionManager.getInstance()
            sm0.createSession(project, "apply_SysReq")
            try {
                StereotypesHelper.addStereotype(element, stereo)
                sm0.closeSession(project)
            } catch (Exception e) {
                sm0.cancelSession(project)
                return [error: "Failed to apply SAF_SystemRequirement: " + e.getMessage()]
            }
        }

        def sm = SessionManager.getInstance()
        sm.createSession(project, "set_req_tags")
        try {
            StereotypesHelper.setStereotypePropertyValue(element, stereo, "id", reqId)
            StereotypesHelper.setStereotypePropertyValue(element, stereo, "text", text)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to set tagged values: " + e.getMessage() + ". Check that SAF_SystemRequirement has 'id' and 'text' tags defined."]
        }

        return [elementId: elementId, reqId: reqId, text: text, tagsSet: 2]
    }

    @McpTool(name = "saf_create_relationship", description = "[SAF] Create a relationship using SAF semantics (satisfy, derive, trace, refine, verify, allocate). Maps each SAF type to the correct SysML relationship + SAF stereotype. Also supports raw types (composition, dependency, generalization, etc.) for mixed use. For raw SysML-only relationships, use create_relationship instead.")
    Map safCreateRelationship(Map<String, Object> args) {
        def type = (args.get("type") ?: "dependency") as String
        def sourceId = args.get("sourceId") as String
        def targetId = args.get("targetId") as String

        if (sourceId == null || sourceId.isEmpty()) return [error: "sourceId is required"]
        if (targetId == null || targetId.isEmpty()) return [error: "targetId is required"]

        def relInfo = SAF_RELATIONSHIP_TYPES[type.toLowerCase()]
        if (relInfo == null) {
            def valid = SAF_RELATIONSHIP_TYPES.keySet().sort().join(", ")
            return [error: "Unknown relationship type: " + type + ". Valid types: " + valid]
        }

        def sysmlType = relInfo[0] as String
        def stereotypeName = relInfo[1] as String

        def project = getProject()
        def source = resolveElement(sourceId)
        def target = resolveElement(targetId)
        if (source == null) return [error: "Source element not found: " + sourceId]
        if (target == null) return [error: "Target element not found: " + targetId]

        def ef = getFactory()
        def rel = null
        def sm = SessionManager.getInstance()
        sm.createSession(project, "saf_create_relationship")
        try {
            switch (sysmlType) {
                case "abstraction":
                    def abs = ef.createAbstractionInstance()
                    abs.getClient().add((NamedElement) source)
                    abs.getSupplier().add((NamedElement) target)
                    ModelElementsManager.getInstance().addElement(abs, source.getOwner() ?: source)
                    rel = abs
                    break
                case "dependency":
                    def dep = ef.createDependencyInstance()
                    dep.getClient().add((NamedElement) source)
                    dep.getSupplier().add((NamedElement) target)
                    ModelElementsManager.getInstance().addElement(dep, source.getOwner() ?: source)
                    rel = dep
                    break
                case "composition":
                    def assoc = ef.createAssociationInstance()
                    def ends = assoc.getOwnedEnd()
                    if (ends.size() >= 1) ends.get(0).setType((Type) source)
                    if (ends.size() >= 2) {
                        ends.get(1).setType((Type) target)
                        ends.get(1).setAggregation(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum.COMPOSITE)
                    }
                    ModelElementsManager.getInstance().addElement(assoc, source.getOwner() ?: source)
                    rel = assoc
                    break
                case "association":
                    def assoc = ef.createAssociationInstance()
                    def ends = assoc.getOwnedEnd()
                    if (ends.size() >= 1) ends.get(0).setType((Type) source)
                    if (ends.size() >= 2) ends.get(1).setType((Type) target)
                    ModelElementsManager.getInstance().addElement(assoc, source.getOwner() ?: source)
                    rel = assoc
                    break
                case "generalization":
                    def gen = ef.createGeneralizationInstance()
                    gen.setSpecific((Classifier) source)
                    gen.setGeneral((Classifier) target)
                    rel = gen
                    break
                case "controlflow":
                    def flow = ef.createControlFlowInstance()
                    flow.setSource((com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode) source)
                    flow.setTarget((com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode) target)
                    rel = flow
                    break
                case "objectflow":
                    def flow = ef.createObjectFlowInstance()
                    flow.setSource((com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode) source)
                    flow.setTarget((com.nomagic.uml2.ext.magicdraw.activities.mdfundamentalactivities.ActivityNode) target)
                    rel = flow
                    break
                case "connector":
                    def conn = ef.createConnectorInstance()
                    def connEnds = conn.getEnd()
                    if (connEnds.size() >= 1) connEnds.get(0).setRole(source)
                    if (connEnds.size() >= 2) connEnds.get(1).setRole(target)
                    rel = conn
                    break
            }

            if (stereotypeName != null && !stereotypeName.isEmpty() && rel != null) {
                def st = findStereotype(stereotypeName)
                if (st != null) {
                    StereotypesHelper.addStereotype(rel, st)
                }
            }

            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        return [id: rel.getID(), type: type, sysmlType: sysmlType, stereotype: stereotypeName, sourceId: sourceId, targetId: targetId]
    }

    @McpTool(name = "saf_query_viewpoint", description = "[SAF] Query elements filtered by SAF domain (architecture_management, operational, conceptual, physical) and aspect (requirement, structure, behavior, interface, context, traceability). Returns matching elements with their SAF kind and domain metadata. For unfiltered element search, use find_elements or find_elements_by_type.")
    List safQueryViewpoint(Map<String, Object> args) {
        def domain = (args.get("domain") ?: "") as String
        def aspect = (args.get("aspect") ?: "") as String
        def parentId = args.get("parentId") as String

        // Determine which SAF element kinds are relevant for this domain
        def relevantKinds = getKindsForViewpoint(domain.toLowerCase(), aspect.toLowerCase())

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [[error: "Root not found"]]

        def allElements = []
        collectAll(root, allElements, 0)

        def filtered = allElements.findAll { elem ->
            def stereos = elem.stereotypes
            def anyMatch = relevantKinds.any { kind ->
                def mapping = CONCEPT_MAP[kind]
                if (mapping == null) return false
                def stereoName = mapping[1] as String
                if (stereoName == null) return false
                if (stereos.contains(stereoName)) return true
                // Also match the human type
                if (elem.type.toLowerCase().contains(mapping[0].toLowerCase())) return true
                return false
            }
            return anyMatch
        }

        return filtered
    }

    List getKindsForViewpoint(String domain, String aspect) {
        def kinds = []
        switch (domain) {
            case "architecture_management":
            case "am":
                kinds = ["stakeholder", "concern", "requirement", "comment"]
                break
            case "operational":
            case "o":
                kinds = ["operational_performer", "operational_capability", "operational_process", "operational_activity", "mission", "requirement"]
                break
            case "conceptual":
            case "c":
                kinds = ["conceptual_system", "conceptual_function", "conceptual_function_action", "system_process", "conceptual_interface", "proxy_port", "connector", "requirement", "exchange_type"]
                break
            case "physical":
            case "p":
                kinds = ["physical_system", "physical_product", "proxy_port", "connector", "requirement"]
                break
            default:
                kinds = CONCEPT_MAP.keySet() as List
        }

        // Narrow by aspect if specified
        if (!aspect.isEmpty()) {
            switch (aspect) {
                case "requirement":
                case "rq":
                    kinds = kinds.findAll { it == "requirement" || it == "system_requirement" }
                    break
                case "structure":
                case "st":
                    kinds = kinds.findAll { it.contains("system") || it.contains("structure") || it.contains("performer") || it.contains("product") }
                    break
                case "behavior":
                case "pb":
                    kinds = kinds.findAll { it.contains("function") || it.contains("process") || it.contains("activity") }
                    break
                case "interface":
                case "if":
                    kinds = kinds.findAll { it.contains("interface") || it.contains("port") || it.contains("connector") }
                    break
                case "context":
                case "cx":
                    kinds = kinds.findAll { it == "stakeholder" || it == "concern" || it == "mission" || it == "operational_performer" }
                    break
                case "traceability":
                case "tm":
                    kinds = ["requirement"]
                    break
            }
        }

        return kinds
    }

    void collectAll(parent, List results, int depth) {
        if (depth > 20) return
        try {
            for (child in parent.getOwnedElement()) {
                if (child instanceof NamedElement) {
                    def name = child.getName() ?: ""
                    def stereos = StereotypesHelper.getStereotypes(child).collect { it.getName() }
                    results.add([
                        id: child.getID(),
                        name: name,
                        type: child.getHumanType(),
                        stereotypes: stereos,
                        parentId: child.getOwner() != null ? child.getOwner().getID() : ""
                    ])
                }
                collectAll(child, results, depth + 1)
            }
        } catch (ignored) {}
    }

    def createByType(String type) {
        def ef = getFactory()
        switch (type.toLowerCase()) {
            case "package": return ef.createPackageInstance()
            case "class": return ef.createClassInstance()
            case "interface": return ef.createInterfaceInstance()
            case "activity": return ef.createActivityInstance()
            case "property": return ef.createPropertyInstance()
            case "port": return ef.createPortInstance()
            case "proxyport": return ef.createProxyPortInstance()
            case "connector": return ef.createConnectorInstance()
            case "comment": return ef.createCommentInstance()
            case "callbehavioraction": return ef.createCallBehaviorActionInstance()
            case "activitypartition": return ef.createActivityPartitionInstance()
            case "opaqueeaction": return ef.createOpaqueActionInstance()
            case "valuetype":
            case "datatype": return ef.createDataTypeInstance()
            case "constraint": return ef.createConstraintInstance()
            default: throw new IllegalArgumentException("Unsupported type: " + type)
        }
    }

    /* ---- SAF IR tools ---- */

    // Map stereotype name -> SAF kind
    static final STEREO_TO_KIND = [:]
    static {
        CONCEPT_MAP.each { kind, mapping ->
            def stereoName = mapping[1] as String
            if (stereoName != null) {
                STEREO_TO_KIND[stereoName] = kind
            }
        }
    }

    // Map SAF kind -> domain
    static final KIND_TO_DOMAIN = [
        "stakeholder": "architecture_management",
        "concern":     "architecture_management",
        "system_requirement": "architecture_management",
        "requirement": "architecture_management",
        "operational_performer": "operational",
        "operational_capability": "operational",
        "system_capability": "conceptual",
        "operational_story": "operational",
        "system_function": "conceptual",
        "operational_process": "operational",
        "operational_activity": "operational",
        "mission": "operational",
        "conceptual_system": "conceptual",
        "conceptual_function": "conceptual",
        "conceptual_function_action": "conceptual",
        "system_process": "conceptual",
        "conceptual_interface": "conceptual",
        "exchange_type": "conceptual",
        "physical_system": "physical",
        "physical_product": "physical",
        "proxy_port": "conceptual",
        "port": "conceptual",
        "connector": "conceptual",
        "activity_partition": "conceptual",
        "comment": "architecture_management",
    ]

    @McpTool(name = "saf_find_elements_by_type", description = "[SAF] Find elements by SysML type and/or stereotype. Returns results enriched with SAF kind and SAF domain metadata. For plain element search without SAF enrichment, use find_elements_by_type. For qualifiedName-based search, use find_elements.")
    List safFindElementsByType(Map<String, Object> args) {
        def typeFilter = (args.get("type") ?: "") as String
        def stereoFilter = (args.get("stereotype") ?: "") as String
        def parentId = args.get("parentId") as String
        def nameFilter = (args.get("name") ?: "") as String

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [[error: "Root not found"]]

        def results = []
        def searchResults = []
        collectAll(root, searchResults, 0)

        searchResults.each { elem ->
            boolean match = true
            if (!typeFilter.isEmpty() && !elem.type.toLowerCase().contains(typeFilter.toLowerCase())) match = false
            if (!stereoFilter.isEmpty() && !elem.stereotypes.any { it.toLowerCase().contains(stereoFilter.toLowerCase()) }) match = false
            if (!nameFilter.isEmpty() && !elem.name.toLowerCase().contains(nameFilter.toLowerCase())) match = false
            if (match) {
                results.add([
                    id: elem.id,
                    name: elem.name,
                    type: elem.type,
                    stereotypes: elem.stereotypes,
                    safKind: resolveSafKind(elem.stereotypes),
                    safDomain: resolveSafDomain(elem.stereotypes),
                    parentId: elem.parentId
                ])
            }
        }

        return results
    }

    @McpTool(name = "saf_get_element_details", description = "[SAF] Get detailed SAF information about an element by ID. Returns name, type, SAF kind, SAF domain, tagged values, owned elements, and traceability relationships. For plain SysML details without SAF metadata, use get_element_details. For qualifiedName lookup, use get_element_info.")
    Map safGetElementDetails(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        if (elementId == null || elementId.isEmpty()) return [error: "elementId is required"]

        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def stereos = []
        try {
            for (st in element.getAppliedStereotype()) {
                def n = st.getName()
                if (n != null && !n.isEmpty()) stereos.add(n)
            }
        } catch (ignored) {}

        def safKind = resolveSafKind(stereos)
        def safDomain = resolveSafDomain(stereos)

        // Collect tagged values
        def taggedValues = [:]
        try {
            for (st in element.getAppliedStereotype()) {
                def stereoName = st.getName()
                if (stereoName == null) continue
                def values = StereotypesHelper.getStereotypePropertyValues(element, st)
                if (values != null) {
                    values.each { entry ->
                        def tagName = entry.getKey() as String
                        def tagVal = entry.getValue()
                        taggedValues[tagName] = tagVal != null ? tagVal.toString() : ""
                    }
                }
            }
        } catch (ignored) {}

        // Collect owned elements (summary)
        def owned = []
        try {
            for (child in element.getOwnedElement()) {
                def childStereos = []
                try {
                    for (st in child.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) childStereos.add(n)
                    }
                } catch (ignored) {}
                owned.add([
                    id: child.getID(),
                    name: (child instanceof NamedElement) ? ((NamedElement) child).getName() : "",
                    type: child.getHumanType(),
                    stereotypes: childStereos,
                    safKind: resolveSafKind(childStereos)
                ])
            }
        } catch (ignored) {}

        // Collect traceability relationships
        def traceabilityRels = collectTraceability(element)

        return [
            id: elementId,
            name: (element instanceof NamedElement) ? ((NamedElement) element).getName() : "",
            qualifiedName: tryNext { element.getQualifiedName() } ?: "",
            type: element.getHumanType(),
            stereotypes: stereos,
            safKind: safKind,
            safDomain: safDomain,
            taggedValues: taggedValues,
            ownedElements: owned,
            traceability: traceabilityRels,
            parentId: element.getOwner() != null ? element.getOwner().getID() : ""
        ]
    }

    @McpTool(name = "saf_build_traceability_chain", description = "[SAF] Build a traceability chain graph starting from a requirement element. Follows satisfy, derive, trace, refine, and verify relationships using BFS traversal. Returns nodes and edges with SAF metadata. Only available in the SAF toolset — use this for impact analysis and requirement coverage.")
    Map safBuildTraceabilityChain(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def maxDepth = (args.get("maxDepth") as Integer) ?: 3

        if (elementId == null || elementId.isEmpty()) return [error: "elementId is required"]

        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def visited = [(elementId): true] as LinkedHashMap
        def edges = []
        def nodes = [buildNode(element)]

        bfsTraceChain(element, visited, edges, nodes, maxDepth)

        return [
            rootId: elementId,
            rootName: (element instanceof NamedElement) ? ((NamedElement) element).getName() : "",
            nodes: nodes,
            edges: edges,
            totalNodes: nodes.size(),
            totalEdges: edges.size()
        ]
    }

    @McpTool(name = "saf_check_consistency", description = "[SAF] Run SAF model consistency checks including orphan requirements detection, broken traceability chains, stereotype compliance, and cross-domain alignment (conceptual↔physical refinement). Returns a structured issue list and summary statistics.")
    Map safCheckConsistency(Map<String, Object> args) {
        def parentId = args.get("parentId") as String
        def checks = (args.get("checks") as List) ?: ["orphan_requirements", "broken_chains", "stereotype_compliance", "cross_domain_alignment"]

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [error: "Root not found"]

        def issues = []
        def summary = [:]

        // Collect all elements
        def allElems = []
        collectAll(root, allElems, 0)

        def reqElements = allElems.findAll { resolveSafKind(it.stereotypes) in ["system_requirement", "requirement"] }
        def systemElements = allElems.findAll { resolveSafKind(it.stereotypes) in ["conceptual_system", "physical_system", "operational_performer"] }

        summary.totalElements = allElems.size()
        summary.requirements = reqElements.size()
        summary.systems = systemElements.size()

        if (checks.contains("orphan_requirements")) {
            reqElements.each { req ->
                def hasTrace = hasTraceabilityLink(req.id)
                if (!hasTrace) {
                    issues.add([
                        severity: "warning",
                        check: "orphan_requirements",
                        elementId: req.id,
                        elementName: req.name,
                        message: "Requirement has no traceability relationships (satisfy/derive/trace/refine)"
                    ])
                }
            }
        }

        if (checks.contains("broken_chains")) {
            def knownIds = allElems.collect { it.id } as Set
            reqElements.each { req ->
                def targets = getTraceTargets(req.id)
                targets.each { targetId ->
                    if (!knownIds.contains(targetId)) {
                        issues.add([
                            severity: "error",
                            check: "broken_chains",
                            elementId: req.id,
                            elementName: req.name,
                            message: "Traceability link points to non-existent element: " + targetId
                        ])
                    }
                }
            }
        }

        if (checks.contains("stereotype_compliance")) {
            allElems.each { elem ->
                if (elem.stereotypes.isEmpty() && elem.type != "Package" && elem.type != "Comment") {
                    // Check if it should have a SAF stereotype
                    def shouldHaveStereo = elem.type in ["Class", "Activity", "ProxyPort", "Port"]
                    if (shouldHaveStereo) {
                        issues.add([
                            severity: "info",
                            check: "stereotype_compliance",
                            elementId: elem.id,
                            elementName: elem.name,
                            message: "Element has no SAF stereotype applied"
                        ])
                    }
                }
            }
        }

        if (checks.contains("cross_domain_alignment")) {
            // Check that conceptual systems have corresponding physical systems
            def conceptualSystems = allElems.findAll { resolveSafKind(it.stereotypes) == "conceptual_system" }
            def physicalSystems = allElems.findAll { resolveSafKind(it.stereotypes) in ["physical_system", "physical_product"] }

            conceptualSystems.each { cs ->
                def hasPhysicalRefinement = hasRefinementLink(cs.id)
                if (!hasPhysicalRefinement && physicalSystems.size() > 0) {
                    issues.add([
                        severity: "warning",
                        check: "cross_domain_alignment",
                        elementId: cs.id,
                        elementName: cs.name,
                        message: "Conceptual system has no refinement link to physical system"
                    ])
                }
            }
        }

        summary.checksRun = checks
        summary.issuesCount = issues.size()
        summary.errors = issues.findAll { it.severity == "error" }.size()
        summary.warnings = issues.findAll { it.severity == "warning" }.size()
        summary.infos = issues.findAll { it.severity == "info" }.size()

        return [
             summary: summary,
             issues: issues
        ]
    }

    @McpTool(name = "saf_get_viewpoint_views", description = "[SAF] Find diagrams that conform to a SAF viewpoint. Search by short code (AM, OV, CV, PV) or name. Returns diagrams ranked by conformance score based on how much of their content matches the viewpoint's element kinds.")
    Map safGetViewpointViews(Map<String, Object> args) {
        def viewpointCode = args.get("viewpointCode") as String
        def viewpointName = args.get("viewpointName") as String
        def parentId = args.get("parentId") as String
        def includeContent = (args.get("includeContent") as Boolean) ?: false

        // Resolve viewpoint domain from code or name
        def domain = resolveViewpointDomain(viewpointCode, viewpointName)
        if (domain == null) {
            return [
                error: "Unknown viewpoint. Use viewpointCode (AM, OV, CV, PV) or viewpointName (architecture_management, operational, conceptual, physical).",
                viewpointCode: viewpointCode,
                viewpointName: viewpointName
            ]
        }

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [error: "Root not found"]

        // Get relevant kinds for this viewpoint
        def relevantKinds = getKindsForViewpoint(domain, "")

        // Collect all diagrams
        def allDiagrams = []
        collectDiagrams(root, allDiagrams)

        // Filter diagrams that conform to this viewpoint
        def conformingViews = []
        for (dInfo in allDiagrams) {
            def diagram = resolveElement(dInfo.id)
            if (diagram == null) continue

            // Check if diagram content matches viewpoint kinds
            def diagramElements = []
            collectDiagramElements(diagram, diagramElements, 0)

            // Count how many elements match the viewpoint's kinds
            def matchCount = 0
            for (elemInfo in diagramElements) {
                def elemKind = resolveSafKind(elemInfo.stereotypes)
                if (relevantKinds.contains(elemKind) && !elemKind.isEmpty()) {
                    matchCount++
                }
            }

            // A diagram conforms if it has at least some matching elements
            if (matchCount > 0) {
                def viewInfo = [
                    diagramId: dInfo.id,
                    name: dInfo.name,
                    type: dInfo.type,
                    matchCount: matchCount,
                    totalElements: diagramElements.size(),
                    conformance: diagramElements.size() > 0 ? Math.round(matchCount * 100.0 / diagramElements.size()) : 0
                ]

                if (includeContent) {
                    viewInfo.content = diagramElements.collect { elemInfo ->
                        buildNode(resolveElement(elemInfo.id))
                    }.findAll { it != null }
                }

                conformingViews << viewInfo
            }
        }

        // Sort by conformance descending
        conformingViews.sort { a, b -> b.conformance <=> a.conformance }

        return [
            viewpoint: [
                domain: domain,
                code: VIEWPOINT_CODE[domain] ?: "",
                name: VIEWPOINT_NAME[domain] ?: ""
            ],
            views: conformingViews,
            viewCount: conformingViews.size(),
            relevantKinds: relevantKinds
        ]
    }

    /* ---- Viewpoint resolution helpers ---- */

    // Short code -> domain
    static final VIEWPOINT_CODE = [
        "am": "architecture_management",
        "ov": "operational",
        "cv": "conceptual",
        "pv": "physical"
    ]

    // Domain -> display name
    static final VIEWPOINT_NAME = [
        "architecture_management": "Architecture Management",
        "operational": "Operational View",
        "conceptual": "Conceptual View",
        "physical": "Physical View"
    ]

    String resolveViewpointDomain(String code, String name) {
        if (code != null && !code.isEmpty()) {
            return VIEWPOINT_CODE[code.toLowerCase()]
        }
        if (name != null && !name.isEmpty()) {
            def lower = name.toLowerCase()
            for (entry in VIEWPOINT_NAME) {
                if (entry.key.toLowerCase().contains(lower) || entry.value.toLowerCase().contains(lower)) {
                    return entry.key
                }
            }
        }
        return null
    }

    void collectDiagrams(def parent, List results) {
        if (parent == null) return
        try {
            for (child in parent.getOwnedElement()) {
                if (child.getHumanType().contains("Diagram")) {
                    results.add([
                        id: child.getID(),
                        name: (child instanceof NamedElement) ? ((NamedElement) child).getName() : "",
                        type: child.getHumanType()
                    ])
                }
                collectDiagrams(child, results)
            }
        } catch (ignored) {}
    }

    void collectDiagramElements(def diagram, List results, int depth) {
        if (depth > 15) return
        if (diagram == null) return
        try {
            for (child in diagram.getOwnedElement()) {
                def childStereos = []
                try {
                    for (st in child.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) childStereos.add(n)
                    }
                } catch (ignored) {}

                results.add([
                    id: child.getID(),
                    name: (child instanceof NamedElement) ? ((NamedElement) child).getName() : "",
                    type: child.getHumanType(),
                    stereotypes: childStereos
                ])
                collectDiagramElements(child, results, depth + 1)
            }
        } catch (ignored) {}
    }

    @McpTool(name = "saf_export_viewpoint", description = "[SAF] Export a SAF viewpoint as a structured intermediate representation (IR). Returns all viewpoint-relevant elements with SAF metadata, tagged values, and intra-viewpoint relationships. For downloading/reporting rather than ad-hoc queries.")
    Map safExportViewpoint(Map<String, Object> args) {
        def domain = (args.get("domain") ?: "") as String
        def aspect = (args.get("aspect") ?: "") as String
        def parentId = args.get("parentId") as String

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [error: "Root not found"]

        def relevantKinds = getKindsForViewpoint(domain.toLowerCase(), aspect.toLowerCase())
        def allElems = []
        collectAll(root, allElems, 0)

        def filtered = allElems.findAll { elem ->
            if (relevantKinds.isEmpty()) return true
            relevantKinds.any { kind ->
                def mapping = CONCEPT_MAP[kind]
                if (mapping == null) return false
                def stereoName = mapping[1] as String
                if (stereoName == null) return false
                if (elem.stereotypes.contains(stereoName)) return true
                return false
            }
        }

        def nodes = filtered.collect { elem ->
            def taggedValues = [:]
            try {
                def el = resolveElement(elem.id)
                if (el != null) {
                    for (st in el.getAppliedStereotype()) {
                        def stereoName = st.getName()
                        if (stereoName == null) continue
                        def values = StereotypesHelper.getStereotypePropertyValues(el, st)
                        if (values != null) {
                            values.each { entry ->
                                def tagName = entry.getKey() as String
                                def tagVal = entry.getValue()
                                taggedValues[tagName] = tagVal != null ? tagVal.toString() : ""
                            }
                        }
                    }
                }
            } catch (ignored) {}

            [
                id: elem.id,
                name: elem.name,
                type: elem.type,
                stereotypes: elem.stereotypes,
                safKind: resolveSafKind(elem.stereotypes),
                safDomain: resolveSafDomain(elem.stereotypes),
                taggedValues: taggedValues,
                parentId: elem.parentId
            ]
        }

        def edges = []
        filtered.each { nodeElem ->
            def traceRels = collectTraceability(resolveElement(nodeElem.id))
            traceRels.each { rel ->
                if (filtered.any { it.id == rel.targetId }) {
                    edges.add([
                        sourceId: nodeElem.id,
                        targetId: rel.targetId,
                        type: rel.type,
                        safKind: resolveSafKind(rel.targetStereotypes ?: [])
                    ])
                }
            }
        }

        return [
            viewpoint: [domain: domain, aspect: aspect],
            nodes: nodes,
            edges: edges,
            nodeCount: nodes.size(),
            edgeCount: edges.size()
        ]
    }

    /* ---- Helper methods for SAF IR tools ---- */

    String resolveSafKind(List stereos) {
        if (stereos == null) return ""
        for (stName in stereos) {
            if (STEREO_TO_KIND.containsKey(stName)) return STEREO_TO_KIND[stName]
        }
        return ""
    }

    String resolveSafDomain(List stereos) {
        def kind = resolveSafKind(stereos)
        return KIND_TO_DOMAIN[kind] ?: ""
    }

    List collectTraceability(def element) {
        def rels = []
        if (element == null) return rels

        try {
            for (dep in element.getClientDependency()) {
                def depStereos = []
                try {
                    for (st in dep.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) depStereos.add(n)
                    }
                } catch (ignored) {}

                for (supplier in dep.getSupplier()) {
                    def supStereos = []
                    try {
                        for (st in supplier.getAppliedStereotype()) {
                            def n = st.getName()
                            if (n != null && !n.isEmpty()) supStereos.add(n)
                        }
                    } catch (ignored) {}

                    rels.add([
                        type: depStereos.isEmpty() ? dep.getHumanType() : depStereos[0],
                        targetId: supplier.getID(),
                        targetName: (supplier instanceof NamedElement) ? ((NamedElement) supplier).getName() : "",
                        targetStereotypes: supStereos
                    ])
                }
            }
        } catch (ignored) {}

        try {
            for (dep in element.getSupplierDependency()) {
                def depStereos = []
                try {
                    for (st in dep.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) depStereos.add(n)
                    }
                } catch (ignored) {}

                for (client in dep.getClient()) {
                    def cliStereos = []
                    try {
                        for (st in client.getAppliedStereotype()) {
                            def n = st.getName()
                            if (n != null && !n.isEmpty()) cliStereos.add(n)
                        }
                    } catch (ignored) {}

                    rels.add([
                        type: depStereos.isEmpty() ? dep.getHumanType() : depStereos[0],
                        targetId: client.getID(),
                        targetName: (client instanceof NamedElement) ? ((NamedElement) client).getName() : "",
                        targetStereotypes: cliStereos
                    ])
                }
            }
        } catch (ignored) {}

        return rels
    }

    boolean hasTraceabilityLink(String elementId) {
        def element = resolveElement(elementId)
        if (element == null) return false
        try {
            if (element.getClientDependency().size() > 0) return true
            if (element.getSupplierDependency().size() > 0) return true
        } catch (ignored) {}
        return false
    }

    List getTraceTargets(String elementId) {
        def element = resolveElement(elementId)
        if (element == null) return []
        def targets = []
        try {
            for (dep in element.getClientDependency()) {
                for (supplier in dep.getSupplier()) {
                    targets.add(supplier.getID())
                }
            }
        } catch (ignored) {}
        return targets
    }

    boolean hasRefinementLink(String elementId) {
        def element = resolveElement(elementId)
        if (element == null) return false
        try {
            for (dep in element.getClientDependency()) {
                def depStereos = []
                try {
                    for (st in dep.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) depStereos.add(n)
                    }
                } catch (ignored) {}
                if (depStereos.contains("Refine")) return true
            }
        } catch (ignored) {}
        try {
            for (dep in element.getSupplierDependency()) {
                def depStereos = []
                try {
                    for (st in dep.getAppliedStereotype()) {
                        def n = st.getName()
                        if (n != null && !n.isEmpty()) depStereos.add(n)
                    }
                } catch (ignored) {}
                if (depStereos.contains("Refine")) return true
            }
        } catch (ignored) {}
        return false
    }

    void bfsTraceChain(def startElement, Map visited, List edges, List nodes, int maxDepth) {
        def queue = [[elem: startElement, depth: 0]]
        while (!queue.isEmpty()) {
            def current = queue.remove(0)
            def elem = current.elem
            def depth = current.depth

            if (depth >= maxDepth) continue

            def rels = collectTraceability(elem)
            rels.each { rel ->
                def targetId = rel.targetId
                if (!visited.containsKey(targetId)) {
                    visited[targetId] = true
                    def target = resolveElement(targetId)
                    if (target != null) {
                        nodes.add(buildNode(target))
                        edges.add([
                            sourceId: elem.getID(),
                            sourceName: (elem instanceof NamedElement) ? ((NamedElement) elem).getName() : "",
                            targetId: targetId,
                            targetName: rel.targetName,
                            type: rel.type
                        ])
                        queue.add([elem: target, depth: depth + 1])
                    }
                }
            }
        }
    }

    Map buildNode(def element) {
        def stereos = []
        try {
            for (st in element.getAppliedStereotype()) {
                def n = st.getName()
                if (n != null && !n.isEmpty()) stereos.add(n)
            }
        } catch (ignored) {}

        return [
            id: element.getID(),
            name: (element instanceof NamedElement) ? ((NamedElement) element).getName() : "",
            type: element.getHumanType(),
            stereotypes: stereos,
            safKind: resolveSafKind(stereos),
            safDomain: resolveSafDomain(stereos)
        ]
    }

    def tryNext(def closure) {
        try { return closure() } catch (ignored) { return null }
    }
}
