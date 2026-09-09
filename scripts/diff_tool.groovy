import com.haarer.saf.mcpserver.handlers.McpTool
import com.haarer.saf.mcpserver.handlers.McpToolArgument
import com.haarer.saf.mcpserver.data.SafDataStore
import com.nomagic.magicdraw.core.Application
import com.nomagic.magicdraw.merge.CompareUtil
import com.nomagic.magicdraw.merge.Optimization
import com.nomagic.magicdraw.merge.macro.MacroChange
import com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory
import com.nomagic.magicdraw.diff.*;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element

class DiffTool {

    static Map STEREO_TO_KIND = [:]
    static Map KIND_TO_DOMAIN = [:]

    static {
        try {
            def idx = SafDataStore.getInstance().getCurrentIndex()
            if (idx == null) return
            def dynStereoToKind = [:]
            def dynKindToDomain = [:]
            def domainPriority = ["architecture_management": 0, "operational": 1, "conceptual": 2, "physical": 3]
            for (c in idx.allConcepts()) {
                def name = c.name()
                def classType = c.classType()
                if (!name || !classType) continue
                def sysmlType = CLASSTYPE_TO_SYSML[classType]
                if (sysmlType == null) continue
                def kindKey = name.toLowerCase()
                    .replaceAll(/[^a-z0-9 ]/, "")
                    .replaceAll(/ /, "_")
                    .replaceAll(/_+/, "_")
                    .replaceAll(/^_|_$/, "")
                if (kindKey.isEmpty()) continue
                def directStereos = idx.getDirectStereotypesForConcept(c.id())
                def stereoName = directStereos.isEmpty() ? null : directStereos[0].name()
                if (stereoName != null) dynStereoToKind[stereoName] = kindKey
                def vps = idx.getViewpointsForConcept(c.id())
                if (!vps.isEmpty()) {
                    def bestDomain = null
                    def bestPriority = 999
                    for (vp in vps) {
                        def domain = vp.domain()
                        def pri = domainPriority[domain] ?: 999
                        if (pri < bestPriority) { bestPriority = pri; bestDomain = domain }
                    }
                    if (bestDomain != null) dynKindToDomain[kindKey] = bestDomain
                }
            }
            STEREO_TO_KIND = dynStereoToKind
            KIND_TO_DOMAIN = dynKindToDomain
        } catch (Exception e) {
            System.err.println("[M3Extract] SAF map build failed: " + e.message)
        }
    }

    private static final CLASSTYPE_TO_SYSML = [
        "Class": "Class",
        "Activity": "Activity",
        "ProxyPort": "ProxyPort",
        "Port": "Port",
        "Requirement": "Class",
        "Comment": "Comment",
        "UseCase": "UseCase",
        "SendSignal": "SendSignal",
        "AcceptEvent": "AcceptEvent",
        "Action": "Action",
        "ValueType": "ValueType",
        "DataType": "DataType",
        "Package": "Package",
        "InterfaceBlock": "InterfaceBlock",
        "Block": "Class"
    ]

    private static final SYSM_LABEL = [
        "Connector": "connects",
        "BindingConnector": "connects",
        "SAF_InterfaceLayerRelationship": "interface-layer relates",
        "SAF_DerivedFromEA": "derives from",
        "Allocate": "allocates",
        "Generalization": "generalizes"
    ]

    @McpTool(name = "diff", description = "Diff two model states (READ-ONLY — never modifies either model). from/to are location specs: 'open_model' (the currently active project) or 'file:<host absolute path to a .mdzip snapshot>'. Pass the live/current model as 'open_model' and the baseline snapshot as the file: side (both open_model is an error; two file: sides are not compared against each other). The tool self-opens any file: side that is not already loaded, compares, closes it, and leaves the previous active project current (facts.diffRestored). Returns { rows, stats, facts }: rows are ADDED / MODIFIED / DELETED / REL_CHANGED with SAF-enriched element identity (id, name, metaclass, stereotypes, safKind/safDomain) and before/after feature reconstruction; relation changes name their endpoint pairs (role id/name, port kind, part). Symbol/presentation-layer noise is counted in stats, never listed as rows. 'scope' (decision A6): pass the id of the element/package/subtree-root that matches your concern to keep only rows inside that subtree — run the full diff first, then tighten; unknown ids yield SCOPE_NOT_FOUND. Identical inputs fast-path returns { rows: [] }. Errors are the typed payload { error: 'diff_error', code, message } with code one of INVALID_LOCATION | TWC_NOT_YET | FILE_MISSING | AMBIGUOUS | RESOLVE_FAILED | OPEN_FAILED | SCOPE_NOT_FOUND | COMPARE_FAILED. NOTE: file: paths resolve on the HOST running Cameo.")
    @McpToolArgument(name = "from", type = "string", description = "Baseline location spec: 'open_model' (the active project) or 'file:<host absolute path>'. Exactly one side must be 'open_model'.")
    @McpToolArgument(name = "to", type = "string", description = "Changed location spec: 'open_model' (the active project) or 'file:<host absolute path>'. Exactly one side must be 'open_model'.")
    @McpToolArgument(name = "scope", type = "string", description = "Optional element/package id (or id of a subtree root). When present, only rows touching that subtree are kept. Resolve which subtree matches the current concern yourself (decision A6); iterate: full diff first, then tighten scope.")
    Map diff(Map<String, Object> args) {
        def from = args.get("from")
        def to = args.get("to")
        def scope = args.getOrDefault("scope", null)
        def facts = [from: from, to: to, scope: scope ?: null]

        // --- typed diff_error helper ---
        def diffError = { String code, String message ->
            return [error: "diff_error", code: code, message: message, facts: facts]
        }

        // --- location spec validation (decision A2; TWC deferred) ---
        for (loc in [from, to]) {
            if (loc == null || loc.empty) return diffError("INVALID_LOCATION", "location spec required; use 'open_model' or 'file:<host path>'")
            if (loc != "open_model" && !loc.startsWith("file:")) {
                if (loc.toLowerCase().startsWith("twc")) {
                    return diffError("TWC_NOT_YET", "Teamwork Cloud versions are not supported in the MVP (decision A2); use 'open_model' or 'file:<host path>'")
                }
                return diffError("INVALID_LOCATION", "unrecognized location spec '" + loc + "'; use 'open_model' or 'file:<host path>'")
            }
            if (loc.startsWith("file:")) {
                def f = new java.io.File(stripPrefix(loc))
                if (!f.exists()) return diffError("FILE_MISSING", "file not found: " + stripPrefix(loc))
            }
        }

        // --- identical-inputs fast-path ---
        def same = false
        if (from == to) {
            same = true
        } else if (from.startsWith("file:") && to.startsWith("file:")) {
            try {
                same = new java.io.File(stripPrefix(from)).getCanonicalPath() == new java.io.File(stripPrefix(to)).getCanonicalPath()
            } catch (ignored) {}
        }
        if (same) {
            def ar = [facts: facts + [identicalInputs: true], stats: [changeCount: 0, symbolChanges: 0, expandedMacros: 0, byKind: [:]]]
            ar["rows"] = []
            return ar
        }

        def pm = Application.getInstance().getProjectsManager()
        def openProj = pm.getActiveProject()
        def allProjects = pm.getProjects()
        allProjects = allProjects != null ? allProjects.toList() : []
        facts["active"] = openProj?.getName()

        def project = openProj
        def descriptor = null
        try {
            if (from == "open_model" && to == "open_model") {
                return diffError("AMBIGUOUS", "both sides 'open_model' refer to the same active project; pass one file: side")
            }
            if (to == "open_model") {
                descriptor = ProjectDescriptorsFactory.createProjectDescriptor(toUri(stripPrefix(from)))
            } else {
                descriptor = ProjectDescriptorsFactory.createProjectDescriptor(toUri(stripPrefix(to)))
            }
        } catch (Exception e) {
            return diffError("RESOLVE_FAILED", "location -> descriptor failed: " + e.toString())
        }

        // Orientation: active project is the open side. Determine from/to projects by location,
        // self-opening a file: side that is not already open so both sides are resolvable.
        def openedByUs = []
        def toProj = null
        def fromProj = null
        try {
            if (from.startsWith("file:") && from != "open_model") {
                def loc = stripPrefix(from)
                fromProj = projectForLoc(loc, allProjects)
                if (fromProj == null) {
                    def loaded = pm.loadProject(ProjectDescriptorsFactory.createProjectDescriptor(toUri(loc)), true)
                    openedByUs.add(loaded)
                    fromProj = loaded
                }
            }
            if (to.startsWith("file:") && to != "open_model") {
                def loc = stripPrefix(to)
                toProj = projectForLoc(loc, allProjects)
                if (toProj == null) {
                    def loaded = pm.loadProject(ProjectDescriptorsFactory.createProjectDescriptor(toUri(loc)), true)
                    openedByUs.add(loaded)
                    toProj = loaded
                }
            }
        } catch (Exception e) {
            return diffError("OPEN_FAILED", "self-open of file: side failed: " + e.toString())
        }
        if (from == "open_model") fromProj = openProj
        if (to == "open_model") toProj = openProj

        facts["fromProj"] = fromProj != null ? (fromProj.getName() + " @ " + (fromProj.getLoadedFrom() ?: "")) : null
        facts["toProj"] = toProj != null ? (toProj.getName() + " @ " + (toProj.getLoadedFrom() ?: "")) : null

        // Resolve the scope subtree (decision A6) against both sides up front; unknown scope -> typed error.
        def scopeIds = null
        if (scope != null && !scope.empty) {
            scopeIds = collectScopeIds(scope, fromProj, toProj)
            if (scopeIds == null || scopeIds.isEmpty()) return diffError("SCOPE_NOT_FOUND", "scope id not found in either side: " + scope)
            facts["scopeSubtreeSize"] = scopeIds.size()
        }

        def out = [facts: facts]
        def diffToRestore = null
        try {
            def eh = [error: { Exception e -> throw e }] as com.nomagic.utils.ErrorHandler
            def diff = CompareUtil.compareProjects(project, descriptor, eh, Optimization.PERFORMANCE)
            diffToRestore = diff
            def changes = diff.getChanges()
            out["changeCount"] = changes?.size() ?: 0

            def rowsByKind = [:].withDefault { [] }
            def rawStats = [:].withDefault { 0 }
            def symbolCount = 0
            def flat = []
            def expanded = 0
            if (changes != null) {
                def stack = changes.toList()
                while (!stack.isEmpty()) {
                    def c = stack.remove(0)
                    if (c instanceof MacroChange) {
                        expanded++
                        def sub = c.getChanges()
                        if (sub != null) stack.addAll(sub)
                    } else {
                        flat.add(c)
                    }
                }
            }
            out["expandedMacros"] = expanded

            for (ch in flat) {
                def d = ch.getDifference()
                def bucket = classifyDiff(d)
                if (bucket == "SYMBOL") { symbolCount++; continue }
                if (d instanceof ElementDifference && isRelationKindRaw(d, fromProj, toProj)) {
                    def relEl = resolveDiffElement(d, fromProj, toProj)
                    def row = buildRelationRow(d, relEl, fromProj, toProj)
                    bucket = "REL_CHANGED"
                    rawStats[bucket]++
                    def keep = scopeIds == null || rowTouchesScope(row, scopeIds)
                    if (keep) rowsByKind[bucket].add(row)
                    continue
                }
                def row = buildRow(ch, d, fromProj, toProj, allProjects)
                bucket = row.kind != null ? row.kind : bucket
                rawStats[bucket]++
                if (bucket in ["ADDED", "MODIFIED", "DELETED", "REL_CHANGED"]) {
                    def keep = scopeIds == null || rowTouchesScope(row, scopeIds)
                    if (keep) rowsByKind[bucket].add(row)
                }
            }
            out["rawStats"] = rawStats
            out["symbolChanges"] = symbolCount

            // Stats reflect the returned (post-scope) rows; flatten into a plain list.
            def flatRows = []
            def byKind = [:].withDefault { 0 }
            for (k in ["REL_CHANGED", "MODIFIED", "DELETED", "ADDED"]) {
                for (r in rowsByKind[k]) { flatRows.add(r); byKind[k]++ }
            }
            out["stats"] = byKind
            out["rows"] = flatRows
        } catch (Exception e) {
            return diffError("COMPARE_FAILED", "compare/extract failed: " + e.toString())
        } finally {
            if (diffToRestore != null) {
                try {
                    CompareUtil.restore(diffToRestore)
                    out["diffRestored"] = true
                } catch (Exception e) {
                    out["restoreFailed"] = e.toString()
                }
            }
            for (p in openedByUs) {
                try {
                    if (p != null && pm.getProjects().contains(p)) pm.closeProject(p)
                } catch (ignored) {}
            }
            if (openedByUs.size() > 0) out["selfOpenedClosed"] = openedByUs.size()
        }
        return out
    }

    /** Rule for decision A6: keep a row if ANY of its referenced element ids lies inside the scope subtree. */
    boolean rowTouchesScope(Map row, Set<String> scopeIds) {
        if (scopeIds == null || scopeIds.isEmpty()) return true
        def ids = []
        if (row.get("element") instanceof Map && row.element.id != null) ids.add(row.element.id)
        if (row.get("source") instanceof Map && row.source.id != null) ids.add(row.source.id)
        if (row.get("elementHint") instanceof Map && row.elementHint.id != null) ids.add(row.elementHint.id)
        if (row.get("before") instanceof Collection) for (e in row.before) { if (e instanceof Map && e.roleId != null) ids.add(e.roleId); if (e instanceof Map && e.partId != null) ids.add(e.partId) }
        if (row.get("after") instanceof Collection) for (e in row.after) { if (e instanceof Map && e.roleId != null) ids.add(e.roleId); if (e instanceof Map && e.partId != null) ids.add(e.partId) }
        for (id in ids) { if (scopeIds.contains(id)) return true }
        return false
    }

    /** Collect all element ids in the subtree rooted at the scope id, across both sides. */
    Set<String> collectScopeIds(String scopeId, def fromProj, def toProj) {
        def all = new LinkedHashSet<String>()
        for (p in [fromProj, toProj]) {
            if (p == null) continue
            def root = safeGet(p, scopeId)
            if (root == null) continue
            if (all.contains(scopeId)) break
            def stack = [root]
            int guard = 0
            while (!stack.isEmpty() && guard++ < 50000) {
                def cur = stack.remove(0)
                if (cur == null) continue
                try { all.add(cur.getID()) } catch (ignored) {}
                try {
                    def owned = cur.getOwnedElement()
                    if (owned != null) for (o in owned) { if (o != null) stack.add(o) }
                } catch (ignored) {}
            }
        }
        return all
    }

    Map buildRow(def ch, def d, def fromProj, def toProj, def allProjects) {
        def row = [:]
        if (d instanceof ElementDifference) {
            def eid = d.getElementID()
            def el = null
            if (d instanceof ElementAddition) {
                el = findElement(eid, [toProj])
            } else if (d instanceof ElementDeletion) {
                el = findElement(eid, [fromProj])
            } else {
                el = findElement(eid, [fromProj, toProj])
            }
            row.element = elementBlock(el)
            if (row.element == null && (d instanceof ElementAddition || d instanceof ElementDeletion)) {
                row.element = [id: eid, name: "(unresolved)", metaclass: null]
            }
            if (d instanceof ElementAddition) {
                row.kind = "ADDED"
            } else if (d instanceof ElementDeletion) {
                row.kind = "DELETED"
            } else if (d instanceof ElementModification) {
                row.kind = "MODIFIED"
                row.feature = d.getChangedPropertyName()
                row.modificationType = modType(d.getModificationInfo())
                def fromEl = findElement(eid, [fromProj])
                def toEl = findElement(eid, [toProj])
                row.before = readFeature(fromEl, d.getChangedPropertyName())
                row.after = readFeature(toEl, d.getChangedPropertyName())
            } else {
                row.kind = classifyDiff(d)
            }
        } else if (d instanceof StereotypeModification) {
            row.kind = "STEREOTYPE"
            row.element = d instanceof ElementDifference ? elementBlock(findElement(d.getElementID(), [fromProj, toProj])) : null
        } else if (d instanceof TagValueModification) {
            row.kind = "TAGVALUE"
            row.element = d instanceof ElementDifference ? elementBlock(findElement(d.getElementID(), [fromProj, toProj])) : null
        } else if (d instanceof DiagramDifference) {
            row.kind = "DIAGRAM"
            row.element = d instanceof ElementDifference ? elementBlock(findElement(d.getElementID(), [fromProj, toProj])) : null
        } else if (d instanceof DomainSpecificCustomizationDifference) {
            row.kind = "DSL_CUSTOMIZATION"
        } else {
            row.kind = classifyDiff(d)
        }
        return row
    }

    /* ---- REL_CHANGED resolution (both sides; endpoint-pairs) ---- */

    Map buildRelationRow(def d, def el, def fromProj, def toProj) {
        def row = [kind: "REL_CHANGED"]
        populateRelation(row, d, el, fromProj, toProj)
        return row
    }

    boolean isRelationKindRaw(def d, def fromProj, def toProj) {
        def el = resolveDiffElement(d, fromProj, toProj)
        return el != null && isRelationKind(el)
    }

    def resolveDiffElement(def d, def fromProj, def toProj) {
        if (!(d instanceof ElementDifference)) return null
        def eid = d.getElementID()
        if (d instanceof ElementAddition) return findElement(eid, [toProj])
        if (d instanceof ElementDeletion) return findElement(eid, [fromProj])
        return findElement(eid, [fromProj, toProj])
    }

    void populateRelation(def row, def d, def el, def fromProj, def toProj) {
        def conn = climbToConnector(el)
        def owner = findOwner(conn ?: el)
        row.source = owner != null ? elementBlock(owner) : null
        row.relation = relationLabel(conn ?: el)
        def hint = el != null ? [id: el.getID(), name: safeName(el), metaclass: humanType(el)] : null
        if (conn != null && conn != el) {
            hint = [id: conn.getID(), name: safeName(conn), metaclass: humanType(conn), via: "climb"]
        }
        row.elementHint = hint
        def eid = conn != null ? conn.getID() : (d instanceof ElementDifference ? d.getElementID() : null)
        def before = []
        def after = []
        try {
            if (d instanceof ElementAddition) {
                def toEl = eid != null && toProj != null ? safeGet(toProj, eid) : null
                after = toEl != null ? readEnds(toEl) : []
            } else if (d instanceof ElementDeletion) {
                def base = eid != null && fromProj != null ? safeGet(fromProj, eid) : null
                before = base != null ? readEnds(base) : []
            } else {
                def base = eid != null && fromProj != null ? safeGet(fromProj, eid) : null
                def toEl = eid != null && toProj != null ? safeGet(toProj, eid) : null
                before = base != null ? readEnds(base) : []
                after = toEl != null ? readEnds(toEl) : []
            }
        } catch (Exception e) {
            row.endError = e.getClass().getSimpleName() + ": " + e.getMessage()
        }
        row.status = d instanceof ElementAddition ? "ADDED" : (d instanceof ElementDeletion ? "DELETED" : "MODIFIED")
        row.before = before
        row.after = after
    }

    String safeName(def el) {
        try { return el instanceof NamedElement ? (el.getName() ?: "") : "" } catch (ignored) { return "" }
    }

    def climbToConnector(def el) {
        if (el == null) return null
        try {
            if (el instanceof com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd) {
                def via = el.get_connectorOfEnd()
                if (via != null) return via
            }
        } catch (ignored) {}
        def cur = el
        int guard = 0
        while (cur != null && guard++ < 8) {
            def h = humanType(cur)
            if (h.toLowerCase().contains("connector")) return cur
            try { cur = cur.getOwner() } catch (ignored) { return null }
        }
        return null
    }

    List readEnds(def conn) {
        def result = []
        try {
            for (ce in conn.getEnd()) {
                def role = null
                def pwp = null
                try { role = ce.getRole() } catch (ignored) {}
                try { pwp = ce.getPartWithPort() } catch (ignored) {}
                def roleId = null
                def roleName = ""
                def roleKind = null
                if (role != null) {
                    roleId = role.getID()
                    roleName = role instanceof NamedElement ? (role.getName() ?: "") : ""
                    roleKind = humanType(role)
                }
                def partName = ""
                def partType = ""
                def partId = pwp != null ? pwp.getID() : null
                if (pwp != null) {
                    partName = pwp instanceof NamedElement ? (pwp.getName() ?: "") : ""
                    try { if (pwp.getType() != null) partType = pwp.getType().getName() } catch (ignored) {}
                }
                result.add([
                    roleName: roleName,
                    roleId: roleId,
                    portKind: roleKind,
                    partWithPort: partName,
                    partTypeName: partType,
                    partId: partId
                ])
            }
        } catch (ignored) {}
        return result
    }

    def findOwner(def el) {
        try {
            def cur = el?.getOwner()
            while (cur != null) {
                if (humanType(cur) in ["Class", "Block", "Package"]) return cur
                cur = cur.getOwner()
            }
        } catch (ignored) {}
        return null
    }

    def projectForLoc(String path, def allProjects) {
        if (allProjects == null || path == null) return null
        def want = new java.io.File(path).getCanonicalPath()
        for (p in allProjects) {
            try {
                def desc = com.nomagic.magicdraw.core.project.ProjectDescriptorsFactory.getDescriptorForProject(p)
                def uri = desc != null ? desc.getURI() : null
                if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                    def f = new java.io.File(uri)
                    if (f.getCanonicalPath() == want) return p
                }
            } catch (ignored) {}
        }
        return null
    }

    def safeGet(def proj, String id) {
        try { return proj.getElementByID(id) } catch (ignored) { return null }
    }

    String relationLabel(def el) {
        if (el == null) return "connects"
        def h = humanType(el)
        def st = stereotypeNames(el)
        for (s in st) { if (SYSM_LABEL.containsKey(s)) return SYSM_LABEL[s] }
        if (SYSM_LABEL.containsKey(h)) return SYSM_LABEL[h]
        if (h.toLowerCase().contains("connector")) return "connects"
        if (h.toLowerCase().contains("general")) return "generalizes"
        return h
    }

    boolean isRelationKind(def el) {
        if (el == null) return false
        def h = humanType(el)
        def st = stereotypeNames(el)
        if (h.toLowerCase().contains("connector") || h.toLowerCase().contains("general")) return true
        for (s in st) { if (SYSM_LABEL.containsKey(s)) return true }
        return false
    }

    /* ---- before/after reconstruction ---- */

    String readFeature(def el, String feature) {
        if (el == null || feature == null) return "null"
        try {
            def v = el.refGetValue(feature)
            return formatValue(v)
        } catch (Exception e) {
            return "<unreadable:" + e.getClass().getSimpleName() + ">"
        }
    }

    String modType(def mi) {
        if (mi == null) return "unknown"
        if (mi instanceof ReferenceModificationInfo) return "reference"
        if (mi instanceof ChangeOwnerInfo) return "owner"
        if (mi instanceof PrimitiveMultiValueModificationInfo) return "multi"
        if (mi instanceof PrimitiveValueModificationInfo) return "primitive"
        if (mi instanceof ValueModificationInfo) return "value"
        return mi.getClass().getSimpleName()
    }

    String formatValue(def v) {
        if (v == null) return "null"
        if (v instanceof Element) {
            try {
                if (v instanceof NamedElement && ((NamedElement) v).getName() != null) {
                    return ((NamedElement) v).getName() + " [" + humanType(v) + "]"
                }
                return "[" + humanType(v) + "]"
            } catch (ignored) {
                return "[element]"
            }
        }
        if (v instanceof Collection || v instanceof List) {
            def names = []
            for (it in v) {
                names.add(formatValue(it))
                if (names.size() >= 8) break
            }
            return (v.size() > names.size() ? names.join(", ") + " (+" + (v.size() - names.size()) + ")" : names.join(", "))
        }
        return String.valueOf(v)
    }

    /* ---- identity ---- */

    Map elementBlock(def el) {
        if (el == null) return null
        def st = stereotypeNames(el)
        def meta = el.getClass().getSimpleName()
        if (meta.endsWith("Impl")) meta = meta.substring(0, meta.length() - 4)
        def kind = resolveSafKind(st)
        return [
            id: el.getID(),
            name: (el instanceof NamedElement ? (el.getName() ?: "") : ""),
            qualifiedName: qualifiedNameOf(el),
            metaclass: meta,
            stereotypes: st,
            safKind: kind ?: null,
            safDomain: (kind ? (KIND_TO_DOMAIN[kind] ?: null) : null),
            parentId: (el.getOwner() != null ? el.getOwner().getID() : null)
        ]
    }

    List stereotypeNames(def el) {
        def names = []
        if (el == null) return names
        try {
            for (s in StereotypesHelper.getStereotypes(el)) {
                def n
                try { n = s.getName() } catch (ignored) { n = null }
                if (n != null && !n.isEmpty()) names.add(n)
            }
        } catch (ignored) {}
        return names
    }

    String resolveSafKind(List stereos) {
        if (stereos == null) return ""
        for (stName in stereos) {
            if (STEREO_TO_KIND.containsKey(stName)) return STEREO_TO_KIND[stName]
        }
        return ""
    }

    def findElement(String id, def projects) {
        if (id == null) return null
        for (p in projects) {
            if (p == null) continue
            try {
                def el = p.getElementByID(id)
                if (el != null) return el
            } catch (ignored) {}
        }
        return null
    }

    String qualifiedNameOf(def e) {
        try {
            def qn = e.getQualifiedName()
            if (qn != null) return qn
        } catch (ignored) {}
        try {
            def parts = []
            def cur = e
            int guard = 0
            while (cur != null && guard++ < 64) {
                def nm = cur instanceof NamedElement ? (cur.getName() ?: "") : ""
                if (nm != null && !nm.isEmpty()) parts.add(0, nm)
                def owner = cur.getOwner()
                if (owner == null || owner == cur) break
                cur = owner
            }
            return parts.join("::")
        } catch (ignored) {}
        return ""
    }

    String humanType(def el) {
        try { return el.getHumanType() } catch (ignored) { return el.getClass().getSimpleName() }
    }

    /* ---- classification (via instanceof on public taxonomy only) ---- */

    String classifyDiff(def d) {
        if (d == null) return "NO_DIFFERENCE"
        if (d instanceof ElementAddition) return "ADDED"
        if (d instanceof ElementDeletion) return "DELETED"
        if (d instanceof ElementModification) return "MODIFIED"
        if (d instanceof StereotypeModification) return "STEREOTYPE"
        if (d instanceof TagValueModification) return "TAGVALUE"
        if (d instanceof ShareDifference) return "SHARE"
        if (d instanceof ModuleUsageDifference) return "MODULE_USAGE"
        if (d instanceof DiagramDifference) return "DIAGRAM"
        if (d instanceof ProjectOptionsDifference) return "PROJECT_OPTIONS"
        if (d instanceof DomainSpecificCustomizationDifference) return "DSL_CUSTOMIZATION"
        return isSymbol(d) ? "SYMBOL" : "OTHER"
    }

    boolean isSymbol(def d) {
        String cn = d.getClass().getName()
        if (cn.startsWith("com.nomagic.magicdraw.diff.symbols")) return true
        try {
            if (d instanceof com.nomagic.magicdraw.diff.symbols.MultiplePersistentPropertyDifference) return true
            if (d instanceof com.nomagic.magicdraw.diff.symbols.SymbolDifference) return true
            if (d instanceof com.nomagic.magicdraw.diff.symbols.SymbolAddition) return true
            if (d instanceof com.nomagic.magicdraw.diff.symbols.SymbolDeletion) return true
        } catch (ignored) {}
        return false
    }

    String stripPrefix(String loc) {
        if (loc.startsWith("file:")) return loc.substring("file:".length())
        return loc
    }

    java.net.URI toUri(String path) {
        return new java.io.File(path).toURI()
    }
}