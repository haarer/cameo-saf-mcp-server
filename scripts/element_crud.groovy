import com.haarer.saf.mcpserver.handlers.McpTool
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.impl.ElementsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.*
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype

class ElementCrud {

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
        def project = getProject()
        def all = StereotypesHelper.getAllStereotypes(project)
        for (st in all) {
            if (st.getName() == name) return st
        }
        return null
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
            case "dependency": return ef.createDependencyInstance()
            case "abstraction": return ef.createAbstractionInstance()
            case "association": return ef.createAssociationInstance()
            case "generalization": return ef.createGeneralizationInstance()
            case "controlflow": return ef.createControlFlowInstance()
            case "objectflow": return ef.createObjectFlowInstance()
            case "activitypartition": return ef.createActivityPartitionInstance()
            case "callbehavioraction": return ef.createCallBehaviorActionInstance()
            case "opaqueeaction": return ef.createOpaqueActionInstance()
            case "initialnode": return ef.createInitialNodeInstance()
            case "activityfinalnode": return ef.createActivityFinalNodeInstance()
            case "flowfinalnode": return ef.createFlowFinalNodeInstance()
            case "decisionnode": return ef.createDecisionNodeInstance()
            case "mergenode": return ef.createMergeNodeInstance()
            case "forknode": return ef.createForkNodeInstance()
            case "joinnode": return ef.createJoinNodeInstance()
            case "inputpin": return ef.createInputPinInstance()
            case "outputpin": return ef.createOutputPinInstance()
            case "valuetype":
            case "datatype": return ef.createDataTypeInstance()
            case "enumeration": return ef.createEnumerationInstance()
            case "signal": return ef.createSignalInstance()
            case "state": return ef.createStateInstance()
            case "pseudostate": return ef.createPseudostateInstance()
            case "statemachine": return ef.createStateMachineInstance()
            case "transition": return ef.createTransitionInstance()
            case "constraint": return ef.createConstraintInstance()
            default: throw new IllegalArgumentException("Unsupported type: " + type)
        }
    }

    @McpTool(name = "create_element", description = "[GENERIC SYSML] Create a model element using raw SysML types (Class, Package, Activity, etc.). Use this for plain SysML modeling without SAF conventions. Returns the new element ID.")
    Map createElement(Map<String, Object> args) {
        def type = (args.get("type") ?: "Class") as String
        def name = args.get("name") as String
        def parentId = args.get("parentId") as String
        def stereotype = args.get("stereotype") as String
        def documentation = args.get("documentation") as String

        if (!name) return [error: "name is required"]
        if (!parentId) return [error: "parentId is required"]

        def project = getProject()
        def parent = resolveElement(parentId)
        if (parent == null) return [error: "Parent element not found: " + parentId]

        def created = null
        def sm = SessionManager.getInstance()
        sm.createSession(project, "create_element")
        try {
            created = createByType(type)
            if (created instanceof NamedElement) {
                ((NamedElement) created).setName(name)
            }
            ModelElementsManager.getInstance().addElement(created, parent)

            if (stereotype != null && !stereotype.isEmpty()) {
                def st = findStereotype(stereotype)
                if (st != null) {
                    StereotypesHelper.addStereotype(created, st)
                } else {
                    sm.cancelSession(project)
                    return [error: "Stereotype not found: " + stereotype]
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
            type: type,
            stereotype: stereotype,
            parentId: parentId
        ]
    }

    @McpTool(name = "set_tagged_values", description = "[GENERIC SYSML] Set tagged values on any stereotyped element. Requires element ID, stereotype name, and a map of tag names to values. For SAF requirement tags, use saf_set_requirement_tags instead.")
    Map setTaggedValues(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def stereotypeName = args.get("stereotype") as String
        def values = args.get("values") as Map

        if (!elementId) return [error: "elementId is required"]
        if (!stereotypeName) return [error: "stereotype is required"]
        if (values == null) return [error: "values map is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def stereo = findStereotype(stereotypeName)
        if (stereo == null) return [error: "Stereotype not found: " + stereotypeName]

        if (!StereotypesHelper.hasStereotype(element, stereo)) {
            def sm2 = SessionManager.getInstance()
            sm2.createSession(project, "apply_stereotype")
            try {
                StereotypesHelper.addStereotype(element, stereo)
                sm2.closeSession(project)
            } catch (Exception e) {
                sm2.cancelSession(project)
                return [error: "Failed to apply stereotype: " + e.getMessage()]
            }
        }

        def setCount = 0
        def sm = SessionManager.getInstance()
        sm.createSession(project, "set_tagged_values")
        try {
            for (entry in values.entrySet()) {
                def tagName = entry.getKey() as String
                def tagValue = entry.getValue()
                StereotypesHelper.setStereotypePropertyValue(element, stereo, tagName, tagValue)
                setCount++
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        return [elementId: elementId, stereotype: stereotypeName, tagsSet: setCount]
    }

    @McpTool(name = "create_relationship", description = "[GENERIC SYSML] Create a relationship using raw SysML types (dependency, abstraction, generalization, association, composition, controlflow, objectflow, connector). Does NOT apply SAF stereotypes. For SAF relationships (satisfy, derive, trace, etc.), use saf_create_relationship instead. Returns the new relationship ID.")
    Map createRelationship(Map<String, Object> args) {
        def type = (args.get("type") ?: "dependency") as String
        def sourceId = args.get("sourceId") as String
        def targetId = args.get("targetId") as String
        def stereotype = args.get("stereotype") as String
        def ownerId = args.get("ownerId") as String

        if (!sourceId) return [error: "sourceId is required"]
        if (!targetId) return [error: "targetId is required"]

        def project = getProject()
        def source = resolveElement(sourceId)
        def target = resolveElement(targetId)
        if (source == null) return [error: "Source element not found: " + sourceId]
        if (target == null) return [error: "Target element not found: " + targetId]

        def ef = getFactory()
        def rel = null
        def sm = SessionManager.getInstance()
        sm.createSession(project, "create_relationship")
        try {
            switch (type.toLowerCase()) {
                case "dependency":
                    def dep = ef.createDependencyInstance()
                    dep.getClient().add((NamedElement) source)
                    dep.getSupplier().add((NamedElement) target)
                    if (ownerId) {
                        def owner = resolveElement(ownerId)
                        if (owner != null) ModelElementsManager.getInstance().addElement(dep, owner)
                    }
                    rel = dep
                    break
                case "abstraction":
                    def abs = ef.createAbstractionInstance()
                    abs.getClient().add((NamedElement) source)
                    abs.getSupplier().add((NamedElement) target)
                    if (ownerId) {
                        def owner = resolveElement(ownerId)
                        if (owner != null) ModelElementsManager.getInstance().addElement(abs, owner)
                    }
                    rel = abs
                    break
                case "generalization":
                    def gen = ef.createGeneralizationInstance()
                    gen.setSpecific((Classifier) source)
                    gen.setGeneral((Classifier) target)
                    rel = gen
                    break
                case "association":
                    def assoc = ef.createAssociationInstance()
                    def ends = assoc.getOwnedEnd()
                    if (ends.size() >= 1) {
                        ends.get(0).setType((Type) source)
                    }
                    if (ends.size() >= 2) {
                        ends.get(1).setType((Type) target)
                    }
                    if (ownerId) {
                        def owner = resolveElement(ownerId)
                        if (owner != null) ModelElementsManager.getInstance().addElement(assoc, owner)
                    }
                    rel = assoc
                    break
                case "composition":
                    def assoc2 = ef.createAssociationInstance()
                    def ends2 = assoc2.getOwnedEnd()
                    if (ends2.size() >= 1) {
                        ends2.get(0).setType((Type) source)
                    }
                    if (ends2.size() >= 2) {
                        ends2.get(1).setType((Type) target)
                        ends2.get(1).setAggregation(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum.COMPOSITE)
                    }
                    if (ownerId) {
                        def owner = resolveElement(ownerId)
                        if (owner != null) ModelElementsManager.getInstance().addElement(assoc2, owner)
                    }
                    rel = assoc2
                    break
                case "controlflow":
                    def flow = ef.createControlFlowInstance()
                    flow.setSource(source)
                    flow.setTarget(target)
                    rel = flow
                    break
                case "objectflow":
                    def flow2 = ef.createObjectFlowInstance()
                    flow2.setSource(source)
                    flow2.setTarget(target)
                    rel = flow2
                    break
                case "connector":
                    def conn = ef.createConnectorInstance()
                    def connEnds = conn.getEnd()
                    if (connEnds.size() >= 1) {
                        connEnds.get(0).setRole(source)
                    }
                    if (connEnds.size() >= 2) {
                        connEnds.get(1).setRole(target)
                    }
                    rel = conn
                    break
                default:
                    sm.cancelSession(project)
                    return [error: "Unsupported relationship type: " + type]
            }

            if (stereotype != null && !stereotype.isEmpty() && rel != null) {
                def st = findStereotype(stereotype)
                if (st != null) {
                    StereotypesHelper.addStereotype(rel, st)
                }
            }

            if (rel != null && type.toLowerCase() != "connector" && type.toLowerCase() != "controlflow" && type.toLowerCase() != "objectflow") {
                if (!ownerId && !(rel instanceof Generalization)) {
                    def owner = source.getOwner() ?: source
                    if (owner instanceof Namespace) {
                        ModelElementsManager.getInstance().addElement(rel, owner)
                    }
                }
            }

            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        return [
            id: rel.getID(),
            type: type,
            stereotype: stereotype,
            sourceId: sourceId,
            targetId: targetId
        ]
    }

    @McpTool(name = "modify_element", description = "[GENERIC SYSML] Modify a model element's name and/or documentation by element ID. Works on any element type. Returns updated element info.")
    Map modifyElement(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def newName = args.get("name") as String
        def newDoc = args.get("documentation") as String

        if (!elementId) return [error: "elementId is required"]

        def project = getProject()
        def element = (Element) project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def sm = SessionManager.getInstance()
        sm.createSession(project, "modify_element")
        try {
            if (newName != null && element instanceof NamedElement) {
                ((NamedElement) element).setName(newName)
            }
            if (newDoc != null) {
                def comments = element.getOwnedComment()
                if (comments != null && !comments.isEmpty()) {
                    comments.iterator().next().setBody(newDoc)
                } else {
                    def comment = getFactory().createCommentInstance()
                    comment.setBody(newDoc)
                    ModelElementsManager.getInstance().addElement(comment, element)
                }
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        def elemName = (element instanceof NamedElement) ? element.getName() : ""
        return [id: elementId, name: elemName, modified: true]
    }

    @McpTool(name = "delete_element", description = "[GENERIC SYSML] Delete any model element by ID. Permanently removes it and all owned sub-elements. Attached relationships are also removed. Use with caution — this is a hard delete with no undo.")
    Map deleteElement(Map<String, Object> args) {
        def elementId = args.get("elementId") as String

        if (elementId == null || elementId.isEmpty()) return [error: "elementId is required"]

        def project = getProject()
        def element = (Element) project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def name = (element instanceof NamedElement) ? element.getName() : null
        def type = element.getHumanType()

        def sm = SessionManager.getInstance()
        sm.createSession(project, "delete_element")
        try {
            ModelElementsManager.getInstance().removeElement(element)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to delete element: " + e.getMessage()]
        }

        def result = [deleted: true, elementId: elementId]
        if (name != null) result.name = name
        result.type = type
        return result
    }

    @McpTool(name = "find_elements_by_type", description = "[GENERIC SYSML] Find elements by raw SysML type name (e.g. Class, Package, Activity) and/or stereotype. Returns results with element ID. For SAF-aware search (with safKind/safDomain), use saf_find_elements_by_type.")
    List findElementsByType(Map<String, Object> args) {
        def typeFilter = (args.get("type") ?: "") as String
        def stereoFilter = (args.get("stereotype") ?: "") as String
        def nameFilter = (args.get("name") ?: "") as String
        def parentId = args.get("parentId") as String

        def project = getProject()
        def root = parentId ? resolveElement(parentId) : project.getPrimaryModel()
        if (root == null) return [[error: "Root not found"]]

        def results = []
        collectMatching(root, typeFilter.toLowerCase(), stereoFilter.toLowerCase(), nameFilter.toLowerCase(), results, 0)
        return results
    }

    void collectMatching(parent, String typeFilter, String stereoFilter, String nameFilter, List results, int depth) {
        if (depth > 20) return
        try {
            for (child in parent.getOwnedElement()) {
                if (child instanceof NamedElement) {
                    def name = child.getName() ?: ""
                    def cname = child.getClass().getName()
                    def stereos = StereotypesHelper.getStereotypes(child).collect { it.getName() }
                    def typeMatch = typeFilter.isEmpty() || cname.toLowerCase().contains(typeFilter)
                    def stereoMatch = stereoFilter.isEmpty() || stereos.any { it.toLowerCase().contains(stereoFilter) }
                    def nameMatch = nameFilter.isEmpty() || name.toLowerCase().contains(nameFilter)

                    if (typeMatch && stereoMatch && nameMatch) {
                        results.add([
                            id: child.getID(),
                            name: name,
                            type: cname,
                            stereotypes: stereos,
                            parentId: child.getOwner() != null ? child.getOwner().getID() : ""
                        ])
                    }
                }
                collectMatching(child, typeFilter, stereoFilter, nameFilter, results, depth + 1)
            }
        } catch (ignored) {}
    }

    @McpTool(name = "get_element_details", description = "[GENERIC SYSML] Get detailed info about an element by its element ID (UUID). Returns name, type, stereotypes, owned elements, and relationships. For SAF-specific details (kind, domain, traceability), use saf_get_element_details. To look up by qualifiedName instead of ID, use get_element_info.")
    Map getElementDetails(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        if (!elementId) return [error: "elementId is required"]

        def project = getProject()
        def elem = resolveElement(elementId)
        if (elem == null) return [error: "Element not found: " + elementId]

        return buildElementDetail(elem, 1)
    }

    Map buildElementDetail(elem, int depth) {
        def name = (elem instanceof NamedElement) ? elem.getName() : ""
        def stereos = StereotypesHelper.getStereotypes(elem).collect { it.getName() }

        def owned = []
        if (depth > 0) {
            try {
                for (child in elem.getOwnedElement()) {
                    if (child instanceof NamedElement) {
                        owned.add(buildElementDetail(child, depth - 1))
                    }
                }
            } catch (ignored) {}
        }

        def rels = []
        try {
            for (dep in elem.getClientDependency()) {
                def depStereos = StereotypesHelper.getStereotypes(dep).collect { it.getName() }
                for (supplier in dep.getSupplier()) {
                    def sname = (supplier instanceof NamedElement) ? supplier.getName() : ""
                    rels.add([type: dep.getHumanType(), direction: "outgoing", target: sname, targetId: supplier.getID(), stereotypes: depStereos])
                }
            }
        } catch (ignored) {}
        try {
            for (gen in elem.getGeneralization()) {
                def general = gen.getGeneral()
                if (general instanceof NamedElement) {
                    rels.add([type: "Generalization", direction: "general", target: general.getName(), targetId: general.getID()])
                }
            }
        } catch (ignored) {}
        try {
            for (spec in elem.getSpecific()) {
                if (spec.getClientDependency() != null) {
                    for (dep in spec.getClientDependency()) {
                        if (dep.getSupplier().contains(elem)) {
                            rels.add([type: dep.getHumanType(), direction: "incoming", source: (spec instanceof NamedElement ? spec.getName() : ""), sourceId: spec.getID()])
                        }
                    }
                }
            }
        } catch (ignored) {}

        return [
            id: elem.getID(),
            name: name,
            type: elem.getHumanType(),
            stereotypes: stereos,
            ownedElements: owned,
            relationships: rels
        ]
    }
}
