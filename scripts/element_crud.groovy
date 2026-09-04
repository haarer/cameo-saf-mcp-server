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
            case "model": return ef.createModelInstance()
            case "class": return ef.createClassInstance()
            case "interface": return ef.createInterfaceInstance()
            case "activity": return ef.createActivityInstance()
            case "opaquebehavior": return ef.createOpaqueBehaviorInstance()
            case "functionbehavior": return ef.createFunctionBehaviorInstance()
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
            case "calloperationaction": return ef.createCallOperationActionInstance()
            case "accepteventaction": return ef.createAcceptEventActionInstance()
            case "acceptcallaction": return ef.createAcceptCallActionInstance()
            case "broadcastsignalaction": return ef.createBroadcastSignalActionInstance()
            case "sendobjectaction": return ef.createSendObjectActionInstance()
            case "sendsignalaction": return ef.createSendSignalActionInstance()
            case "createobjectaction": return ef.createCreateObjectActionInstance()
            case "destroyobjectaction": return ef.createDestroyObjectActionInstance()
            case "readselfaction": return ef.createReadSelfActionInstance()
            case "readstructuralfeatureaction": return ef.createReadStructuralFeatureActionInstance()
            case "readvariableaction": return ef.createReadVariableActionInstance()
            case "addstructuralfeaturevalueaction": return ef.createAddStructuralFeatureValueActionInstance()
            case "addvariablevalueaction": return ef.createAddVariableValueActionInstance()
            case "valuespecificationaction": return ef.createValueSpecificationActionInstance()
            case "startobjectbehavioraction": return ef.createStartObjectBehaviorActionInstance()
            case "replyaction": return ef.createReplyActionInstance()
            case "opaqueeaction":
            case "opaqueaction": return ef.createOpaqueActionInstance()
            case "initialnode": return ef.createInitialNodeInstance()
            case "activityfinalnode": return ef.createActivityFinalNodeInstance()
            case "flowfinalnode": return ef.createFlowFinalNodeInstance()
            case "decisionnode": return ef.createDecisionNodeInstance()
            case "mergenode": return ef.createMergeNodeInstance()
            case "forknode": return ef.createForkNodeInstance()
            case "joinnode": return ef.createJoinNodeInstance()
            case "activityparameternode": return ef.createActivityParameterNodeInstance()
            case "centralbuffernode": return ef.createCentralBufferNodeInstance()
            case "datastorenode": return ef.createDataStoreNodeInstance()
            case "inputpin": return ef.createInputPinInstance()
            case "outputpin": return ef.createOutputPinInstance()
            case "valuepin": return ef.createValuePinInstance()
            case "actioninputpin": return ef.createActionInputPinInstance()
            case "loopnode": return ef.createLoopNodeInstance()
            case "conditionalnode": return ef.createConditionalNodeInstance()
            case "sequencenode": return ef.createSequenceNodeInstance()
            case "structuredactivitynode": return ef.createStructuredActivityNodeInstance()
            case "expansionregion": return ef.createExpansionRegionInstance()
            case "exceptionhandler": return ef.createExceptionHandlerInstance()
            case "valuetype":
            case "datatype": return ef.createDataTypeInstance()
            case "primitivetype": return ef.createPrimitiveTypeInstance()
            case "enumeration": return ef.createEnumerationInstance()
            case "enumerationliteral": return ef.createEnumerationLiteralInstance()
            case "signal": return ef.createSignalInstance()
            case "state": return ef.createStateInstance()
            case "finalstate": return ef.createFinalStateInstance()
            case "pseudostate": return ef.createPseudostateInstance()
            case "region": return ef.createRegionInstance()
            case "statemachine": return ef.createStateMachineInstance()
            case "protocolstatemachine": return ef.createProtocolStateMachineInstance()
            case "transition": return ef.createTransitionInstance()
            case "protocoltransition": return ef.createProtocolTransitionInstance()
            case "trigger": return ef.createTriggerInstance()
            case "connectionpointreference": return ef.createConnectionPointReferenceInstance()
            case "interaction": return ef.createInteractionInstance()
            case "lifeline": return ef.createLifelineInstance()
            case "message": return ef.createMessageInstance()
            case "combinedfragment": return ef.createCombinedFragmentInstance()
            case "interactionoperand": return ef.createInteractionOperandInstance()
            case "interactionuse": return ef.createInteractionUseInstance()
            case "gate": return ef.createGateInstance()
            case "occurrencespecification": return ef.createOccurrenceSpecificationInstance()
            case "executionoccurrencespecification": return ef.createExecutionOccurrenceSpecificationInstance()
            case "stateinvariant": return ef.createStateInvariantInstance()
            case "continuation": return ef.createContinuationInstance()
            case "constraint": return ef.createConstraintInstance()
            case "expression": return ef.createExpressionInstance()
            case "stringexpression": return ef.createStringExpressionInstance()
            case "opaqueexpression": return ef.createOpaqueExpressionInstance()
            case "literalinteger": return ef.createLiteralIntegerInstance()
            case "literalreal": return ef.createLiteralRealInstance()
            case "literalstring": return ef.createLiteralStringInstance()
            case "literalboolean": return ef.createLiteralBooleanInstance()
            case "literalunlimitednatural": return ef.createLiteralUnlimitedNaturalInstance()
            case "literalnull": return ef.createLiteralNullInstance()
            case "usecase": return ef.createUseCaseInstance()
            case "actor": return ef.createActorInstance()
            case "include": return ef.createIncludeInstance()
            case "extend": return ef.createExtendInstance()
            case "extensionpoint": return ef.createExtensionPointInstance()
            case "instancespecification": return ef.createInstanceSpecificationInstance()
            case "slot": return ef.createSlotInstance()
            case "component": return ef.createComponentInstance()
            case "node": return ef.createNodeInstance()
            case "device": return ef.createDeviceInstance()
            case "artifact": return ef.createArtifactInstance()
            case "executionenvironment": return ef.createExecutionEnvironmentInstance()
            case "deployment": return ef.createDeploymentInstance()
            case "deploymentspecification": return ef.createDeploymentSpecificationInstance()
            case "communicationpath": return ef.createCommunicationPathInstance()
            case "componentrealization": return ef.createComponentRealizationInstance()
            case "interfacerealization": return ef.createInterfaceRealizationInstance()
            case "manifestation": return ef.createManifestationInstance()
            case "usage": return ef.createUsageInstance()
            case "substitution": return ef.createSubstitutionInstance()
            case "realization": return ef.createRealizationInstance()
            case "informationitem": return ef.createInformationItemInstance()
            case "informationflow": return ef.createInformationFlowInstance()
            case "collaboration": return ef.createCollaborationInstance()
            case "collaborationuse": return ef.createCollaborationUseInstance()
            case "parameter": return ef.createParameterInstance()
            case "templatebinding": return ef.createTemplateBindingInstance()
            case "templatesignature": return ef.createTemplateSignatureInstance()
            case "templateparameter": return ef.createTemplateParameterInstance()
            case "generalizationset": return ef.createGeneralizationSetInstance()
            case "stereotype": return ef.createStereotypeInstance()
            case "profile": return ef.createProfileInstance()
            case "profileapplication": return ef.createProfileApplicationInstance()
            case "elementimport": return ef.createElementImportInstance()
            case "packageimport": return ef.createPackageImportInstance()
            case "packagemerge": return ef.createPackageMergeInstance()
            case "extension": return ef.createExtensionInstance()
            case "extensionend": return ef.createExtensionEndInstance()
            case "image": return ef.createImageInstance()
            default: throw new IllegalArgumentException("Unsupported type: " + type)
        }
    }

    @McpTool(name = "create_element", description = "Create a new SysML model element (Class, Package, Activity, Port, etc.) as a child of an existing parent element. Optionally apply a stereotype and set documentation. Returns the created element's ID. For SAF-typed elements, use saf_create_element instead.")
    @McpToolArgument(name = "type", type = "string", description = "SysML type: Class, Package, Model, Interface, Activity, OpaqueBehavior, FunctionBehavior, Property, Port, ProxyPort, Connector, Comment, Dependency, Abstraction, Association, Generalization, ControlFlow, ObjectFlow, ActivityPartition, Action (CallBehavior, CallOperation, AcceptEvent, SendSignal, SendObject, CreateObject, DestroyObject, ReadSelf, ReadStructuralFeature, ReadVariable, ValueSpecification, Opaque), ActivityNode (Initial, ActivityFinal, FlowFinal, Decision, Merge, Fork, Join, Parameter, CentralBuffer, DataStore, Input/Output/Value/ActionInput Pin, Loop, Conditional, Structured, ExpansionRegion, ExceptionHandler), DataType, ValueType, PrimitiveType, Enumeration, EnumerationLiteral, Signal, State, FinalState, Pseudostate, Region, StateMachine, ProtocolStateMachine, Transition, ProtocolTransition, Trigger, ConnectionPointReference, Interaction, Lifeline, Message, CombinedFragment, InteractionOperand, InteractionUse, Gate, OccurrenceSpecification, StateInvariant, Continuation, Constraint, Expression, StringExpression, OpaqueExpression, LiteralInteger, LiteralReal, LiteralString, LiteralBoolean, LiteralUnlimitedNatural, LiteralNull, Use Case, Actor, Include, Extend, ExtensionPoint, InstanceSpecification, Slot, Component, Node, Device, Artifact, ExecutionEnvironment, Deployment, DeploymentSpecification, CommunicationPath, ComponentRealization, InterfaceRealization, Manifestation, Usage, Substitution, Realization, InformationItem, InformationFlow, Collaboration, CollaborationUse, Parameter, TemplateBinding, TemplateSignature, TemplateParameter, GeneralizationSet, Stereotype, Profile, ProfileApplication, ElementImport, PackageImport, PackageMerge, Extension, ExtensionEnd, Image, etc.")
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
                com.nomagic.magicdraw.uml2.Elements.setComment(created, documentation)
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

    @McpTool(name = "get_stereotype_tags", description = "Read all applied stereotypes and their property values ('tags') of an element by ID. Unlike saf_get_element_details (which shows only SAF-enriched tagged values), this returns EVERY applied stereotype with ALL its property values — e.g. abbreviation, errorMessage or severity of a validationRule constraint.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the element to inspect", required = true)
    Map getStereotypeTags(Map<String, Object> args) {
        def id = args.get("elementId") as String
        if (!id) return [error: "elementId required"]
        def project = getProject()
        def el = resolveElement(id)
        if (el == null) return [error: "not found: " + id]
        def tags = []
        for (st in StereotypesHelper.getStereotypes(el)) {
            try {
                for (p in st.getAttribute()) {
                    def vals
                    try {
                        vals = StereotypesHelper.getStereotypePropertyValue(el, st, p.getName())
                    } catch (Exception inner) {
                        vals = ["<err> " + inner.getMessage()]
                    }
                    tags.add([stereotype: st.getName(), property: p.getName(), values: vals.collect { String.valueOf(it) }])
                }
            } catch (ignored) {}
        }
        return [id: id, name: (el instanceof NamedElement ? el.getName() : ""), tags: tags]
    }

    @McpTool(name = "apply_stereotype", description = "Apply a stereotype to an element by ID without setting any tag values. Use set_tagged_values to set values afterwards, get_stereotype_tags to verify. Stereotype names are case-sensitive; use spec_list_stereotypes to discover valid names. Applying a stereotype from a used module makes the primary model depend on that module.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the target element", required = true)
    @McpToolArgument(name = "stereotype", type = "string", description = "Exact stereotype name to apply (e.g. 'SAF_SystemRequirement')", required = true)
    Map applyStereotype(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def stereotypeName = args.get("stereotype") as String
        if (!elementId) return [error: "elementId is required"]
        if (!stereotypeName) return [error: "stereotype is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]
        def stereo = findStereotype(stereotypeName)
        if (stereo == null) return [error: "Stereotype not found: " + stereotypeName]
        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        if (StereotypesHelper.hasStereotype(element, stereo)) {
            return [elementId: elementId, stereotype: stereotypeName, applied: false, note: "already applied"]
        }
        def sm = SessionManager.getInstance()
        sm.createSession(project, "apply_stereotype")
        try {
            StereotypesHelper.addStereotype(element, stereo)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to apply stereotype: " + e.getMessage()]
        }
        return [elementId: elementId, stereotype: stereotypeName, applied: true]
    }

    @McpTool(name = "remove_stereotype", description = "Remove an applied stereotype from an element by ID. Succeeds silently (applied=false, note='not applied') if the element does not carry the stereotype.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the target element", required = true)
    @McpToolArgument(name = "stereotype", type = "string", description = "Exact stereotype name to remove", required = true)
    Map removeStereotype(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def stereotypeName = args.get("stereotype") as String
        if (!elementId) return [error: "elementId is required"]
        if (!stereotypeName) return [error: "stereotype is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]
        def stereo = findStereotype(stereotypeName)
        if (stereo == null) return [error: "Stereotype not found: " + stereotypeName]
        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        if (!StereotypesHelper.hasStereotype(element, stereo)) {
            return [elementId: elementId, stereotype: stereotypeName, removed: false, note: "not applied"]
        }
        def sm = SessionManager.getInstance()
        sm.createSession(project, "remove_stereotype")
        try {
            StereotypesHelper.removeStereotype(element, stereo)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to remove stereotype: " + e.getMessage()]
        }
        return [elementId: elementId, stereotype: stereotypeName, removed: true]
    }

    @McpTool(name = "create_relationship", description = '''Create a SysML relationship between two elements. Supported types: dependency, abstraction, generalization, association, composition, controlflow, objectflow, connector. Optionally apply a stereotype. For SAF relationships (satisfy, derive, trace, refine, verify, allocate), use saf_create_relationship instead. Returns the relationship ID.

IMPORTANT: 'composition' here creates a package-level Association whose second end has composite aggregation (association member ends). It does NOT create a block-owned part property. For a part property owned by a whole Block (needed for IBD/BDD internal structure and SAF C1_SCXD), use create_part instead.''')
    @McpToolArgument(name = "type", type = "string", description = "Relationship type: dependency, abstraction, generalization, association, composition, controlflow, objectflow, connector. For block-owned parts use create_part, not composition.")
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
                // Canonical API: sets documentation on the first owned comment, or creates
                // one if none exists; null/empty documentation removes the comment.
                com.nomagic.magicdraw.uml2.Elements.setComment(element, newDoc)
            }
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getMessage()]
        }

        def elemName = (element instanceof NamedElement) ? element.getName() : ""
        return [id: elementId, name: elemName, modified: true]
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
    @McpToolArgument(name = "specLanguage", type = "string", description = "Optional: only return elements whose specification is written in this language (case-insensitive, e.g. 'Groovy', 'Jython', 'OCL2.0', 'Binary'). Elements without a specification are excluded when this filter is set. Use together with type='Constraint' to find code-bearing validation rules.")
    @McpToolArgument(name = "specTextContains", type = "string", description = "Optional: only return elements whose specification body contains this substring (case-insensitive).")
    List findElementsByType(Map<String, Object> args) {
        def typeFilter = (args.get("type") ?: "") as String
        def stereoFilter = (args.get("stereotype") ?: "") as String
        def nameFilter = (args.get("name") ?: "") as String
        def parentId = args.get("parentId") as String
        def scope = ((args.get("scope") ?: "all") as String).toLowerCase()
        def specLanguage = ((args.get("specLanguage") ?: "") as String).toLowerCase()
        def specTextContains = ((args.get("specTextContains") ?: "") as String).toLowerCase()
        boolean specFiltering = !specLanguage.isEmpty() || !specTextContains.isEmpty()

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
                    if (match && specFiltering) {
                        match = specMatches(obj, specLanguage, specTextContains)
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

    boolean specMatches(def elem, String langFilter, String textFilter) {
        def specText = specificationOf(elem) ?: ""
        if (specText.isEmpty()) return false
        int nl = specText.indexOf("\n")
        String langs = (nl >= 0 ? specText.substring(0, nl) : "").toLowerCase()
        String body = (nl >= 0 ? specText.substring(nl + 1) : specText).toLowerCase()
        if (!langFilter.isEmpty()) {
            // language part is a comma-joined list; require exact token match
            boolean has = langs.split(",").any { it.trim() == langFilter }
            if (!has) return false
        }
        if (!textFilter.isEmpty()) {
            if (!body.contains(textFilter)) return false
        }
        return true
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
