import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager
import com.nomagic.magicdraw.openapi.uml.SessionManager
import com.nomagic.uml2.impl.ElementsFactory
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.*

class StructuralTools {

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
            for (st in StereotypesHelper.getAllStereotypes(project)) {
                if (st.getName() == name) return st
            }
        } catch (ignored) {}
        return null
    }

    Map writableCheck(def element) {
        try {
            def ap = com.nomagic.magicdraw.core.ProjectUtilities.getAttachedProject(element)
            if (ap == null) return null
            boolean apRo = false
            try { apRo = ap.isReadOnly() } catch (ignored) {}
            if (apRo) {
                return [error: "Element belongs to used project '" + (ap.getName() ?: "module") + "' which is read-only. Used projects are typically read-only; ask the user how to proceed."]
            }
            def desc = null
            try { desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(ap) } catch (ignored) {}
            boolean remote = desc instanceof com.nomagic.magicdraw.core.project.AbstractRemoteProjectDescriptor
            boolean fileRo = false
            if (!remote && desc != null) {
                try {
                    def uri = desc.getURI()
                    if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                        def f = new File(uri)
                        if (f.exists() && !f.canWrite()) fileRo = true
                    }
                } catch (ignored) {}
            }
            if (remote || fileRo) {
                return [error: "Element belongs to used project '" + (ap.getName() ?: "module") + "' which is read-only. Used projects are typically read-only; ask the user how to proceed."]
            }
        } catch (ignored) {}
        return null
    }

    String typeLabel(def elem) {
        if (elem == null) return null
        return [id: elem.getID(), name: (elem instanceof NamedElement ? elem.getName() : ""), type: elem.getHumanType()]
    }

    String multiLabel(def elem) {
        // elem here is a MultiplicityElement (Property / ConnectorEnd / Port)
        try {
            return com.nomagic.uml2.ext.jmi.helpers.CoreHelper.getMultiplicity((com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement) elem)
        } catch (ignored) {}
        return "1"
    }

    // ------------------------------------------------------------------
    // Tier 1 #1: set_type
    // ------------------------------------------------------------------
    @McpTool(name = "set_type", description = "Set the 'type' (the typed-by classifier, e.g. a Block/Class/Interface) of an existing TypedElement by ID. Works on Property (incl. parts), Port, ProxyPort, Operation parameters, etc. Use this to type a part property with its hardware block after create_part, or to type a port or value property. Returns the element id, its name, and the old/new type. Only applies if the element is a TypedElement.")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the Property/Port/ProxyPort/parameter to type", required = true)
    @McpToolArgument(name = "typeId", type = "string", description = "Element ID of the Class/Block/Interface/DataType to use as the type", required = true)
    Map setType(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def typeId = args.get("typeId") as String
        if (!elementId) return [error: "elementId is required"]
        if (!typeId) return [error: "typeId is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        def type = resolveElement(typeId)
        if (element == null) return [error: "Element not found: " + elementId]
        if (type == null) return [error: "Type element not found: " + typeId]
        if (!(element instanceof TypedElement)) {
            return [error: "Element is not a TypedElement (is " + element.getHumanType() + "); cannot set its type"]
        }
        if (!(type instanceof Type)) {
            return [error: "Type element is not a Type (is " + type.getHumanType() + ")"]
        }

        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        String oldName = null
        try { oldName = element.getType() != null ? (element.getType().getName() ?: "") : null } catch (ignored) {}

        def sm = SessionManager.getInstance()
        sm.createSession(project, "set_type")
        try {
            element.setType((Type) type)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to set type: " + e.getMessage()]
        }

        String newName = null
        try { newName = element.getType() != null ? (element.getType().getName() ?: "") : null } catch (ignored) {}
        return [
            elementId: elementId,
            name: (element instanceof NamedElement ? element.getName() : ""),
            oldType: oldName,
            newType: newName,
            newTypeId: typeId,
            updated: true
        ]
    }

    // ------------------------------------------------------------------
    // Tier 1 #2: create_part
    // ------------------------------------------------------------------
    @McpTool(name = "create_part", description = "Create a block-owned, TYPED part property in one call: creates a Property owned by the given whole Block/Class, sets its type to the given part Block/Class, applies the PartProperty stereotype (and optionally a SAF role stereotype), and sets the multiplicity. This is the canonical way to build an internal part structure for an IBD — unlike SAF 'composition' relationships (which create package-level association ends), create_part puts a typed part property directly on the whole's internal structure. Returns the part property's ID.\n\nIMPORTANT — auto-created companion Association: when the part's aggregation is 'composite' or 'shared', MagicDraw implicitly maintains a backing Association between the whole and the part's type (this association is what appears as the composition line on a BDD). Do NOT also call create_relationship(type='composition') for the same whole/part pair — the companion Association already exists, and adding an explicit one creates a duplicate. Use create_part alone, then draw the line with saf_add_association_paths.")
    @McpToolArgument(name = "wholeBlockId", type = "string", description = "Element ID of the owning Block/Class that will contain the part property", required = true)
    @McpToolArgument(name = "name", type = "string", description = "Name of the part property (e.g. 'raspberryPiZeroW1_1')", required = true)
    @McpToolArgument(name = "partTypeBlockId", type = "string", description = "Element ID of the Block/Class that types this part (e.g. the hardware element)", required = true)
    @McpToolArgument(name = "multiplicity", type = "string", description = "Multiplicity expression, e.g. '1' (default), '0..1', '*' or '0..*'. Applied via Elements.setMultiplicity.")
    @McpToolArgument(name = "roleStereotype", type = "string", description = "Optional SAF role stereotype to apply to the part (e.g. 'SAF_PhysicalInternalRole'). Applied on top of PartProperty.")
    @McpToolArgument(name = "aggregation", type = "string", description = "Optional aggregation kind for the part: 'composite', 'shared', or 'none'. Default 'none'. Note: 'composite' or 'shared' cause MagicDraw to auto-create a companion Association between whole and part type (the composition line on a BDD) — do not also call create_relationship('composition') for the same pair or you'll get a duplicate.")
    Map createPart(Map<String, Object> args) {
        def wholeBlockId = args.get("wholeBlockId") as String
        def name = args.get("name") as String
        def partTypeBlockId = args.get("partTypeBlockId") as String
        def multiplicity = ((args.get("multiplicity") ?: "1") as String).trim()
        def roleStereotype = args.get("roleStereotype") as String
        def aggregation = ((args.get("aggregation") ?: "none") as String).toLowerCase()

        if (!wholeBlockId) return [error: "wholeBlockId is required"]
        if (!name) return [error: "name is required"]
        if (!partTypeBlockId) return [error: "partTypeBlockId is required"]

        def project = getProject()
        def whole = resolveElement(wholeBlockId)
        def partType = resolveElement(partTypeBlockId)
        if (whole == null) return [error: "Whole block not found: " + wholeBlockId]
        if (partType == null) return [error: "Part type not found: " + partTypeBlockId]
        if (!(whole instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier)) {
            return [error: "Whole element is not a Classifier (is " + whole.getHumanType() + "); cannot own a part property"]
        }
        if (!(partType instanceof Type)) {
            return [error: "Part type is not a Type (is " + partType.getHumanType() + ")"]
        }

        def roErr = writableCheck(whole)
        if (roErr != null) return roErr

        def ef = getFactory()
        def part = ef.createPropertyInstance()
        def sm = SessionManager.getInstance()
        sm.createSession(project, "create_part")
        try {
            part.setName(name)
            part.setType((Type) partType)
            ModelElementsManager.getInstance().addElement(part, whole)

            def partStereo = findStereotype("PartProperty")
            if (partStereo != null) {
                StereotypesHelper.addStereotype(part, partStereo)
            }

            if (roleStereotype != null && !roleStereotype.isEmpty()) {
                def roleStereo = findStereotype(roleStereotype)
                if (roleStereo != null) {
                    StereotypesHelper.addStereotype(part, roleStereo)
                } else {
                    sm.cancelSession(project)
                    return [error: "Role stereotype not found: " + roleStereotype + ". Use spec_list_stereotypes to discover valid names. (No element was created.)"]
                }
            }

            try {
                com.nomagic.magicdraw.uml2.Elements.setMultiplicity(multiplicity, (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement) part)
            } catch (Exception e) {
                return [error: "Failed to set multiplicity '" + multiplicity + "': " + e.getMessage()]
            }

            if (aggregation == "composite") {
                part.setAggregation(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum.COMPOSITE)
            } else if (aggregation == "shared") {
                part.setAggregation(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum.SHARED)
            } else {
                part.setAggregation(com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum.NONE)
            }

            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getClass().getName() + ": " + (e.getMessage() ?: "")]
        }

        String typeName = null
        try { typeName = part.getType()?.getName() } catch (ignored) {}
        return [
            id: part.getID(),
            name: name,
            type: part.getHumanType(),
            typedBy: typeName,
            typedById: partTypeBlockId,
            multiplicity: multiplicity,
            stereotypes: StereotypesHelper.getStereotypes(part).collect { it.getName() },
            wholeId: wholeBlockId,
            created: true
        ]
    }

    // ------------------------------------------------------------------
    // Tier 1 #3: get_block_structure
    // ------------------------------------------------------------------
    @McpTool(name = "get_block_structure", description = "Read the internal structure of a Block/Class needed to reconstruct an IBM (internal block diagram): owned parts (name, type, multiplicity, aggregation, stereotypes), owned ports (name, type/interface), and connectors with their end roles (part/port). One call replaces N+1 get_element_details drill-downs when exploring composite structures.")
    @McpToolArgument(name = "blockId", type = "string", description = "Element ID of the Block/Class whose internal structure to read", required = true)
    @McpToolArgument(name = "includeConnectors", type = "boolean", description = "Include owned connectors in the result (default true). Set false to only read parts and ports.")
    Map getBlockStructure(Map<String, Object> args) {
        def blockId = args.get("blockId") as String
        boolean includeConnectors = args.get("includeConnectors") == null ? true : Boolean.parseBoolean(String.valueOf(args.get("includeConnectors")))
        if (!blockId) return [error: "blockId is required"]

        def project = getProject()
        def block = resolveElement(blockId)
        if (block == null) return [error: "Block not found: " + blockId]
        if (!(block instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier)) {
            return [error: "Element is not a Classifier (is " + block.getHumanType() + "); use get_element_details instead"]
        }

        def parts = []
        def ports = []

        def attributes = []
        try { attributes.addAll(block.getAttribute()) } catch (ignored) {}
        if (attributes.isEmpty()) {
            try { attributes.addAll(block.getOwnedAttribute()) } catch (ignored) {}
        }

        for (a in attributes) {
            if (!(a instanceof Property)) continue
            def prop = (Property) a
            // Detect ports by interface, not prop.isPort() (not exposed on this Property variant).
            boolean isPort = prop instanceof com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port
            def typeId = null
            def typeName = null
            try {
                if (prop.getType() != null) {
                    typeId = prop.getType().getID()
                    typeName = prop.getType().getName()
                }
            } catch (ignored) {}

            def aggregation = ""
            try {
                aggregation = prop.isComposite() ? "composite" : (prop.getAggregation() != null ? prop.getAggregation().toString().toLowerCase() : "none")
            } catch (ignored) {}

            def stNames = []
            try {
                stNames = StereotypesHelper.getStereotypes(prop).collect { it.getName() }
            } catch (ignored) {}

            def entry = [
                id: prop.getID(),
                name: (prop.getName() ?: ""),
                type: prop.getHumanType(),
                typedBy: typeName,
                typedById: typeId,
                multiplicity: multiLabel(prop),
                stereotypes: stNames
            ]

            if (isPort) {
                entry.isPort = true
                ports.add(entry)
            } else {
                entry.aggregation = aggregation
                parts.add(entry)
            }
        }

        def connectors = []
        if (includeConnectors) {
            try {
                for (conn in block.getOwnedConnector()) {
                    def ends = []
                    try {
                        for (ce in conn.getEnd()) {
                            def role = ce.getRole()
                            def partWithPort = ce.getPartWithPort()
                            ends.add([
                                roleName: (role instanceof NamedElement ? role.getName() : (role != null ? role.getHumanType() : "")),
                                roleId: (role != null ? role.getID() : null),
                                partWithPort: (partWithPort instanceof NamedElement ? partWithPort.getName() : (partWithPort != null ? partWithPort.getID() : null)),
                                multiplicity: multiLabel(ce)
                            ])
                        }
                    } catch (ignored) {}
                    connectors.add([
                        id: conn.getID(),
                        name: (conn.getName() ?: ""),
                        type: conn.getHumanType(),
                        ends: ends
                    ])
                }
            } catch (ignored) {}
        }

        return [
            blockId: blockId,
            blockName: (block instanceof NamedElement ? block.getName() : ""),
            parts: parts,
            ports: ports,
            connectors: connectors
        ]
    }

    @McpTool(name = "set_multiplicity", description = "Set the multiplicity of a Property, Port, or ConnectorEnd by ID using an expression such as '1', '0..1', '*' or '0..*'. Uses Elements.setMultiplicity. (Companion helper for create_part/set_type.)")
    @McpToolArgument(name = "elementId", type = "string", description = "Element ID of the Property/Port/ConnectorEnd", required = true)
    @McpToolArgument(name = "multiplicity", type = "string", description = "Multiplicity expression, e.g. '1', '0..1', '*', '0..*'", required = true)
    Map setMultiplicity(Map<String, Object> args) {
        def elementId = args.get("elementId") as String
        def multiplicity = ((args.get("multiplicity") ?: "1") as String).trim()
        if (!elementId) return [error: "elementId is required"]
        if (!multiplicity) return [error: "multiplicity is required"]

        def project = getProject()
        def element = resolveElement(elementId)
        if (element == null) return [error: "Element not found: " + elementId]
        if (!(element instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement)) {
            return [error: "Element is not a MultiplicityElement (is " + element.getHumanType() + ")"]
        }
        def roErr = writableCheck(element)
        if (roErr != null) return roErr

        def sm = SessionManager.getInstance()
        sm.createSession(project, "set_multiplicity")
        try {
            com.nomagic.magicdraw.uml2.Elements.setMultiplicity(multiplicity, (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement) element)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: "Failed to set multiplicity '" + multiplicity + "': " + e.getMessage()]
        }
        return [elementId: elementId, name: (element instanceof NamedElement ? element.getName() : ""), multiplicity: multiLabel(element), updated: true]
    }

    // ------------------------------------------------------------------
    // Tier 2: create_connector — internal-structure connector owned by a block
    // ------------------------------------------------------------------
    @McpTool(name = "create_connector", description = "Create a properly-wired internal-structure Connector owned by a whole Block, connecting two part ports (the standard representation used by an IBD). Each end is explicitly created with its role (the port on the part's type) and partWithPort (the part property of the whole), and its multiplicity is set. The connector is named from its ends, validated (each part's type must own its port, the part must be owned by the whole), and added to the whole block's ownedConnectors so it renders between the parts in the whole's internal block diagram. Returns the connector ID and per-end detail.")
    @McpToolArgument(name = "wholeBlockId", type = "string", description = "Element ID of the owning Block/StructuredClassifier whose internal structure the connector lives in (e.g. the Lawnbot block)", required = true)
    @McpToolArgument(name = "end1PartId", type = "string", description = "Element ID of the first part property (a part of wholeBlockId, e.g. the Raspberry Pi part)", required = true)
    @McpToolArgument(name = "end1PortId", type = "string", description = "Element ID of the first port, owned by end1Part's type (e.g. the Pi 'i2c' port)", required = true)
    @McpToolArgument(name = "end2PartId", type = "string", description = "Element ID of the second part property (a part of wholeBlockId)", required = true)
    @McpToolArgument(name = "end2PortId", type = "string", description = "Element ID of the second port, owned by end2Part's type (e.g. the Motor HAT 'i2c' port)", required = true)
    @McpToolArgument(name = "name", type = "string", description = "Optional connector name; defaults to 'end1.summary <-> end2.summary'")
    @McpToolArgument(name = "end1Multiplicity", type = "string", description = "Optional multiplicity for end 1 (default '1')")
    @McpToolArgument(name = "end2Multiplicity", type = "string", description = "Optional multiplicity for end 2 (default '1')")
    Map createConnector(Map<String, Object> args) {
        def wholeBlockId = args.get("wholeBlockId") as String
        def end1PartId = args.get("end1PartId") as String
        def end1PortId = args.get("end1PortId") as String
        def end2PartId = args.get("end2PartId") as String
        def end2PortId = args.get("end2PortId") as String
        def nameArg = (args.get("name") ?: "") as String
        def end1Mult = ((args.get("end1Multiplicity") ?: "1") as String).trim()
        def end2Mult = ((args.get("end2Multiplicity") ?: "1") as String).trim()

        if (!wholeBlockId) return [error: "wholeBlockId is required"]
        if (!end1PortId) return [error: "end1PortId is required"]
        if (!end2PortId) return [error: "end2PortId is required"]
        if (!end1PartId) return [error: "end1PartId is required"]
        if (!end2PartId) return [error: "end2PartId is required"]

        def project = getProject()
        def whole = resolveElement(wholeBlockId)
        def p1 = resolveElement(end1PartId)
        def port1 = resolveElement(end1PortId)
        def p2 = resolveElement(end2PartId)
        def port2 = resolveElement(end2PortId)
        if (whole == null) return [error: "Whole block not found: " + wholeBlockId]
        if (port1 == null) return [error: "Port 1 not found: " + end1PortId]
        if (port2 == null) return [error: "Port 2 not found: " + end2PortId]
        if (p1 == null) return [error: "Part 1 not found: " + end1PartId]
        if (p2 == null) return [error: "Part 2 not found: " + end2PartId]

        if (!(whole instanceof com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.StructuredClassifier)) {
            return [error: "Whole element is not a StructuredClassifier (is " + whole.getHumanType() + "); cannot own a connector"]
        }

        def checkRole = { def port, def part, String label ->
            if (!(port instanceof com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port) &&
                !(port instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property)) {
                return "End '" + label + "' is not a Port/Property (is " + port.getHumanType() + ")"
            }
            // Part must be a property of the whole (or the whole itself) - part-owns-connector consistency.
            if (part != null && !(part instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property)) {
                return "End '" + label + "' part is not a Property (is " + part.getHumanType() + ")"
            }
            // Port must be connectable and, if a part is given, owned by that part's type.
            if (part instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property) {
                def partType = part.getType()
                if (partType != null) {
                    boolean ownedBy = tryOwns(partType, port)
                    if (!ownedBy) {
                        return "End '" + label + "' port '" + (port.getName()) + "' is not owned by part '" + part.getName() + "' type '" + partType.getName() + "'"
                    }
                }
            }
            return null
        }

        def err1 = checkRole(port1, p1, "1")
        if (err1 != null) return [error: err1]
        def err2 = checkRole(port2, p2, "2")
        if (err2 != null) return [error: err2]

        def roErr = writableCheck(whole)
        if (roErr != null) return roErr

        def ef = getFactory()
        def conn = ef.createConnectorInstance()
        def sm = SessionManager.getInstance()
        // Resolve the SysML "NestedConnectorEnd" stereotype so each ConnectorEnd can
        // record the containing-property path (the part within the whole) in its
        // propertyPath tagged value — the canonical IBD nested-end wiring.
        def ncEndStereotype = null
        try {
            def sp = com.nomagic.magicdraw.sysml.util.SysMLProfile.getInstance(whole)
            if (sp != null) ncEndStereotype = sp.getNestedConnectorEnd()
        } catch (ignored) {}
        sm.createSession(project, "create_connector")
        try {
            def ends = conn.getEnd()
            boolean hadEnds = ends != null && ends.size() >= 2
            if (!hadEnds) {
                // Explicitly create the two ConnectorEnds so they are properly contained.
                ends = [ef.createConnectorEndInstance(), ef.createConnectorEndInstance()]
                for (ce in ends) {
                    ModelElementsManager.getInstance().addElement(ce, conn)
                }
            }
            wireEnd(ends.get(0), port1, p1, end1Mult, ncEndStereotype)
            wireEnd(ends.get(1), port2, p2, end2Mult, ncEndStereotype)
            String pn1 = (p1 instanceof NamedElement ? p1.getName() : "")
            String pn2 = (p2 instanceof NamedElement ? p2.getName() : "")
            String portName1 = (port1 instanceof NamedElement ? port1.getName() : "")
            String portName2 = (port2 instanceof NamedElement ? port2.getName() : "")
            String defaultName = (pn1 ? pn1 + "::" : "") + portName1 + " <-> " + (pn2 ? pn2 + "::" : "") + portName2
            if (conn instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement) {
                conn.setName(nameArg ?: defaultName)
            }
            ModelElementsManager.getInstance().addElement(conn, whole)
            sm.closeSession(project)
        } catch (Exception e) {
            sm.cancelSession(project)
            return [error: e.getClass().getName() + ": " + (e.getMessage() ?: "")]
        }

        String pn1 = (p1 instanceof NamedElement ? p1.getName() : "")
        String pn2 = (p2 instanceof NamedElement ? p2.getName() : "")
        String portName1 = (port1 instanceof NamedElement ? port1.getName() : "")
        String portName2 = (port2 instanceof NamedElement ? port2.getName() : "")
        def endsOut = []
        try {
            for (ce in conn.getEnd()) {
                def role = ce.getRole()
                def pwp = ce.getPartWithPort()
                endsOut.add([
                    role: (role instanceof NamedElement ? role.getName() : (role != null ? role.getHumanType() : "")),
                    partWithPort: (pwp instanceof NamedElement ? pwp.getName() : (pwp != null ? pwp.getID() : null)),
                    multiplicity: multiLabel(ce)
                ])
            }
        } catch (ignored) {}
        return [
            id: conn.getID(),
            name: (conn.getName() ?: ""),
            type: conn.getHumanType(),
            wholeId: wholeBlockId,
            end1: (pn1 ? pn1 + "::" : "") + portName1,
            end2: (pn2 ? pn2 + "::" : "") + portName2,
            ends: endsOut,
            created: true
        ]
    }

    private boolean tryOwns(def owner, def candidate) {
        try {
            for (owned in owner.getOwnedElement()) {
                if (owned == candidate) return true
                if (owned.getID() == candidate.getID()) return true
            }
        } catch (ignored) {}
        return false
    }

    private void wireEnd(def ce, def port, def part, String mult, def ncEndStereotype) {
        ce.setRole((com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement) port)
        if (part != null) {
            ce.setPartWithPort((com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property) part)
        }
        try {
            com.nomagic.magicdraw.uml2.Elements.setMultiplicity(mult, (com.nomagic.uml2.ext.magicdraw.classes.mdkernel.MultiplicityElement) ce)
        } catch (ignored) {}
        // Proper IBD nested-end treatment: mark the end with the SysML
        // "NestedConnectorEnd" stereotype so the part-within-whole path is recorded
        // in its propertyPath tagged value (mirrors what the Cameo GUI produces).
        if (ncEndStereotype != null) {
            try {
                com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper.addStereotype(ce, ncEndStereotype)
                if (part instanceof com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property) {
                    def sp = com.nomagic.magicdraw.sysml.util.SysMLProfile.getInstance(ce)
                    sp.nestedConnectorEnd().setPropertyPath(ce, [part])
                }
            } catch (ignored) {}
        }
    }
}

// Groovy allows trailing unused-block style; none needed.
