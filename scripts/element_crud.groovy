import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.magicdraw.uml.Finder as Finder
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

    Map writableCheck(def element) {
        try {
            def ap = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProject(element)
            if (ap == null) return null
            boolean apRo = false
            try { apRo = ap.isReadOnly() } catch (ignored) {}
            if (apRo) {
                def pname = ap.getName() ?: "module"
                return [error: "Element belongs to used project '" + pname + "' which is read-only. Used projects are typically read-only; ask the user how to proceed."]
            }
            def desc = null
            try { desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(ap) } catch (ignored) {}
            boolean remote = desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor
            boolean fileRo = false
            String location = ""
            if (!remote && desc != null) {
                try {
                    def uri = desc.getURI()
                    location = uri != null ? uri.toString() : ""
                    if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                        def f = new File(uri)
                        if (f.exists() && !f.canWrite()) fileRo = true
                    }
                } catch (ignored) {}
            } else if (remote) {
                try { location = desc.getURI()?.toString() ?: "" } catch (ignored) {}
            }
            if (remote || fileRo) {
                def pname = ap.getName() ?: "module"
                return [error: "Element belongs to used project '" + pname + "' which is read-only" + (remote ? " (remote/TWC)" : " (file not writable)") + ". Used projects are typically read-only; ask the user how to proceed."]
            }
        } catch (ignored) {}
        return null
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

    @McpTool(name = "create_element", description = "Create a new SysML model element (Class, Package, Activity, Port, etc.) as a child of an existing parent element. Optionally apply a stereotype and set documentation. Returns the created element's ID. For SAF-typed elements, use saf_create_element instead.")
    @McpToolArgument(name = "type", type = "string", description = "SysML type: Class, Package, Interface, Activity, Port, ProxyPort, Connector, Comment, Dependency, Abstraction, Association, Generalization, ControlFlow, ObjectFlow, DataType, Enumeration, Signal, State, StateMachine, Transition, Constraint, etc.")
    @McpToolArgument(name = "name", type = "string", description = "Name for the new element", required = true)
    @McpToolArgument(name = "parentId", type = "string", description = "Element ID of the parent to contain the new element", required = true)
    @McpToolArgument(name = "stereotype", type = "string", description = "Optional stereotype name to apply to the element")
    @McpToolArgument(name = "documentation", type = "string", description = "Optional documentation text stored as a comment")
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

        def roErr = writableCheck(parent)
        if (roErr != null) return roErr

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
            return [error: e.getClass().getName() + ": " + (e.getMessage() ?: "")]
        }

        return [
            id: created.getID(),
            name: name,
            type: type,
            stereotype: stereotype,
            parentId: parentId
        ]
    }

    @McpTool(name = "set_tagged_values", description = "Set tagged values (stereotype properties) on an element. The element must have the specified stereotype applied; if not, it will be applied automatically. Pass a map of tag names to values. For SAF requirement id/text, use saf_set_requirement_tags instead.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the target element", required = true)
    @McpToolArgument(name = "stereotype", type = "string", description = "Name of the stereotype whose tagged values to set", required = true)
    @McpToolArgument(name = "values", type = "object", description = "Map of tag name to value (e.g. {\"id\": \"REQ-001\", \"priority\": \"high\"})", required = true)
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

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

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

    @McpTool(name = "create_relationship", description = "Create a SysML relationship between two elements. Supported types: dependency, abstraction, generalization, association, composition, controlflow, objectflow, connector. Optionally apply a stereotype. For SAF relationships (satisfy, derive, trace, refine, verify, allocate), use saf_create_relationship instead. Returns the relationship ID.")
    @McpToolArgument(name = "type", type = "string", description = "Relationship type: dependency, abstraction, generalization, association, composition, controlflow, objectflow, connector")
    @McpToolArgument(name = "sourceId", type = "string", description = "Element ID of the source", required = true)
    @McpToolArgument(name = "targetId", type = "string", description = "Element ID of the target", required = true)
    @McpToolArgument(name = "stereotype", type = "string", description = "Optional stereotype to apply to the relationship")
    @McpToolArgument(name = "ownerId", type = "string", description = "Optional parent element ID to own the relationship")
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

        def roErr = writableCheck(source)
        if (roErr != null) return roErr
        roErr = writableCheck(target)
        if (roErr != null) return roErr

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

    @McpTool(name = "modify_element", description = "Update the name and/or documentation of an existing element by its ID. At least one of 'name' or 'documentation' must be provided. Returns updated element info.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the element to modify", required = true)
    @McpToolArgument(name = "name", type = "string", description = "New name for the element")
    @McpToolArgument(name = "documentation", type = "string", description = "New documentation text (stored as comment)")
    Map modifyElement(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def newName = args.get("name") as String
        def newDoc = args.get("documentation") as String

        if (!elementId) return [error: "elementId is required"]

        def project = getProject()
        def element = (Element) project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

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

    @McpTool(name = "update_specification", description = "Set the specification body (executable code or expression text) of a Constraint by replacing/creating its ValueSpecification as an OpaqueExpression with one language/body pair. Also works directly on OpaqueExpression and OpaqueBehavior elements (sets their language/body lists). Use get_element_details first to see the current specification ('<language>\\n<body>' format).")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the Constraint (or of an OpaqueExpression/OpaqueBehavior)", required = true)
    @McpToolArgument(name = "language", type = "string", description = "Language tag for the body (e.g. 'Groovy', 'Jython', 'OCL2.0', 'English'). Default: 'Groovy'.")
    @McpToolArgument(name = "body", type = "string", description = "New specification body text (code)", required = true)
    Map updateSpecification(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def language = (args.get("language") ?: "Groovy") as String
        def body = args.get("body") as String

        if (!elementId) return [error: "elementId is required"]
        if (body == null) return [error: "body is required"]

        def project = getProject()
        def element = (Element) project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        // Resolve the value specification holder.
        def holder = null
        if (element instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) {
            holder = element
        } else if (element instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression ||
                   element instanceof com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior) {
            holder = element
        } else {
            return [error: "Element is not a Constraint, OpaqueExpression or OpaqueBehavior: " + element.getHumanType()]
        }

        def sm = SessionManager.getInstance()
        // language/body are EMF multi-valued features without generated setters;
        // mutate them reflectively via eSet + UMLPackage literals.
        def LIT = com.nomagic.uml2.ext.magicdraw.metadata.UMLPackage.Literals
        sm.createSession(project, "update_specification")
        try {
            if (holder instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) {
                def constraint = (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) holder
                def spec = null
                try { spec = constraint.getSpecification() } catch (ignored) {}
                if (!(spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression)) {
                    // Replace LiteralString/Expression/plain spec with a fresh OpaqueExpression
                    def oe = getFactory().createOpaqueExpressionInstance()
                    ModelElementsManager.getInstance().addElement(oe, constraint)
                    constraint.setSpecification(oe)
                    spec = oe
                }
                spec.eSet(LIT.OPAQUE_EXPRESSION__LANGUAGE, [language])
                spec.eSet(LIT.OPAQUE_EXPRESSION__BODY, [body])
            } else if (holder instanceof com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior) {
                holder.eSet(LIT.OPAQUE_BEHAVIOR__LANGUAGE, [language])
                holder.eSet(LIT.OPAQUE_BEHAVIOR__BODY, [body])
            } else {
                holder.eSet(LIT.OPAQUE_EXPRESSION__LANGUAGE, [language])
                holder.eSet(LIT.OPAQUE_EXPRESSION__BODY, [body])
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to update specification: " + e.getMessage()]
        }

        return [id: elementId, language: language, updated: true]
    }

    @McpTool(name = "delete_element", description = "Permanently delete a model element by ID. Removes the element, all owned sub-elements, and attached relationships. This is a hard delete with no undo.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the element to delete", required = true)
    Map deleteElement(Map<String, Object> args) {
        def elementId = args.get("elementId") as String

        if (elementId == null || elementId.isEmpty()) return [error: "elementId is required"]

        def project = getProject()
        def element = (Element) project.getElementByID(elementId)
        if (element == null) return [error: "Element not found: " + elementId]

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

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

    @McpTool(name = "find_elements_by_type", description = '''Recursively search for model elements by type name substring, stereotype substring, and/or name substring using Finder.byTypeRecursively. Searches ALL model content including used projects/modules by default (scope='all'); use scope='primary' to restrict to the primary model. Returns matching elements with their IDs, names, types, stereotypes, owning project, and writability. All filters are optional — omit to get all elements. For SAF-enriched results (safKind, safDomain, tagged values), use saf_find_elements_by_type instead.

SAF stereotype naming convention: use full stereotype names with the SAF_ prefix (e.g., 'SAF_ConceptualSystem', not 'conceptual_system').
All parameters are case-insensitive — don't retry with different casing.
Use spec_list_stereotypes to see all available stereotype names in the model.''')
    @McpToolArgument(name = "type", type = "string", description = "Substring to match against element type name (case-insensitive). Leave empty to match all types.")
    @McpToolArgument(name = "stereotype", type = "string", description = "Substring to match against applied stereotype names (case-insensitive). Leave empty to match all. Use full SAF_ stereotype names (e.g., 'SAF_ConceptualSystem'), not concept kind names.")
    @McpToolArgument(name = "name", type = "string", description = "Substring to match against element names (case-insensitive). Leave empty to match all.")
    @McpToolArgument(name = "parentId", type = "string", description = "Element ID to search within. Omit to search the entire model including used projects.")
    @McpToolArgument(name = "scope", type = "string", description = "'all' (default) searches primary model plus all used projects/modules; 'primary' searches only the primary model.")
    List findElementsByType(Map<String, Object> args) {
        def typeFilter = (args.get("type") ?: "") as String
        def stereoFilter = (args.get("stereotype") ?: "") as String
        def nameFilter = (args.get("name") ?: "") as String
        def parentId = args.get("parentId") as String
        def scope = ((args.get("scope") ?: "all") as String).toLowerCase()

        def project = getProject()

        List roots
        if (parentId) {
            def root = resolveElement(parentId)
            if (root == null) return [[error: "Root not found"]]
            roots = [root]
        } else if (scope == "primary") {
            roots = [project.getPrimaryModel()]
            if (roots[0] == null) return [[error: "No primary model"]]
        } else {
            roots = getModelRoots(project)
            if (roots.isEmpty()) return [[error: "No model roots found"]]
        }

        def results = []
        for (root in roots) {
            def rootInfo = rootProjectInfo(project, root)
            def fi = Finder.byTypeRecursively()
            def all = fi.find(root, null)

            all.stream()
                .filter { obj -> obj instanceof NamedElement }
                .filter { obj ->
                    def match = true
                    if (match && !typeFilter.isEmpty()) {
                        match = (obj.getClass().getName() ?: "").toLowerCase().contains(typeFilter.toLowerCase())
                    }
                    if (match && !stereoFilter.isEmpty()) {
                        def stereos = StereotypesHelper.getStereotypes(obj)
                        match = stereos.any { st -> (st.getName() ?: "").toLowerCase().contains(stereoFilter.toLowerCase()) }
                    }
                    if (match && !nameFilter.isEmpty()) {
                        match = (obj.getName() ?: "").toLowerCase().contains(nameFilter.toLowerCase())
                    }
                    return match
                }
                .forEach { obj ->
                    def entry = [
                        id: obj.getID(),
                        name: obj.getName() ?: "",
                        type: obj.getClass().getName(),
                        stereotypes: StereotypesHelper.getStereotypes(obj).collect { it.getName() },
                        parentId: obj.getOwner() != null ? obj.getOwner().getID() : "",
                        project: rootInfo.name,
                        primary: rootInfo.primary,
                        writable: rootInfo.writable
                    ]
                    refineOwnership(project, obj, entry)
                    results.add(entry)
                }
        }
        return results
    }

    @McpTool(name = "get_elements_details_batch", description = "Get detailed info for multiple model elements by their IDs in a single call. Pass an array of element IDs. Returns a list of element details (name, type, stereotypes, owned elements, relationships). Use this instead of calling get_element_details multiple times to eliminate N+1 drill-down.")
    @McpToolArgument(name = "ids", type = "array", description = "Array of element IDs to get details for. Each ID should be a string element ID from previous search results.", required = true)
    List getElementsDetailsBatch(Map<String, Object> args) {
        def ids = args.get("ids") as List
        if (ids == null || ids.isEmpty()) return [[error: "ids array is required"]]

        def project = getProject()
        return ids.collect { id ->
            def elem = project.getElementByID(id as String)
            if (elem == null) return [id: id, error: "Element not found"]
            return buildElementDetail(elem, 1)
        }
    }

    @McpTool(name = "get_element_details", description = "Get full details about a model element by ID, including name, type, stereotypes, owned sub-elements, and relationships (dependencies, generalizations). For SAF-enriched details (kind, domain, tagged values, traceability), use saf_get_element_details. For lookup by qualified name, use get_element_info.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the element to inspect", required = true)
    Map getElementDetails(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        if (!elementId) return [error: "elementId is required"]

        def project = getProject()
        def elem = resolveElement(elementId)
        if (elem == null) return [error: "Element not found: " + elementId]

        return buildElementDetail(elem, 1)
    }

    String documentationOf(def elem) {
        def bodies = []
        try {
            for (c in elem.getOwnedComment()) {
                def b = c.getBody()
                if (b != null && !b.isEmpty()) bodies.add(b)
            }
        } catch (ignored) {}
        return bodies.join("\n\n")
    }

    String specificationOf(def elem) {
        // Constraint.specification is a ValueSpecification (not a NamedElement);
        // extract its expression text so constraint code stays readable.
        def spec = null
        try { spec = elem.getSpecification() } catch (ignored) { return "" }
        if (spec == null) return ""
        String typeName = spec.getClass().getSimpleName()
        try {
            if (spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.LiteralString) {
                return spec.getValue() ?: ""
            }
        } catch (ignored) {}
        try {
            if (spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.OpaqueExpression) {
                def langs = spec.getLanguage()
                def bodies = spec.getBody()
                if (bodies == null || bodies.isEmpty()) return ""
                return ((langs != null) ? langs.join(",") : "?") + "\n" + bodies.join("\n--\n")
            }
        } catch (ignored) {}
        try {
            if (spec instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Expression) {
                def sym = spec.getSymbols()
                return (sym != null && !sym.isEmpty()) ? sym.join("\n--\n") : "[" + typeName + "]"
            }
        } catch (ignored) {}
        return "[" + typeName + "]"
    }

    Map buildElementDetail(elem, int depth) {
        def name = (elem instanceof NamedElement) ? elem.getName() : ""
        def stereos = StereotypesHelper.getStereotypes(elem).collect { it.getName() }

        def owned = []
        if (depth > 0) {
            try {
                for (child in elem.getOwnedElement()) {
                    if (child instanceof Comment) {
                        // Comments are not NamedElements; expose them so
                        // documentation stored as comments stays discoverable.
                        owned.add([
                            id: child.getID(),
                            name: "",
                            type: child.getHumanType(),
                            body: child.getBody() ?: "",
                            stereotypes: [],
                            ownedElements: [],
                            relationships: []
                        ])
                    } else if (child instanceof NamedElement) {
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

        def result = [
            id: elem.getID(),
            name: name,
            type: elem.getHumanType(),
            stereotypes: stereos,
            documentation: documentationOf(elem),
            ownedElements: owned,
            relationships: rels
        ]
        // Opaque behaviors carry executable code as parallel language/body lists.
        try {
            if (elem instanceof com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.OpaqueBehavior) {
                def langs = elem.getLanguage()
                def bodies = elem.getBody()
                result.languages = (langs != null) ? langs.collect { it } : []
                result.body = (bodies != null && !bodies.isEmpty()) ? bodies.join("\n--\n") : ""
            }
        } catch (ignored) {}
        // Constraints carry their expression in specification.
        try {
            if (elem instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Constraint) {
                result.specification = specificationOf(elem)
            }
        } catch (ignored) {}
        return result
    }
}
