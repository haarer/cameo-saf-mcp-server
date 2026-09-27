package com.haarer.saf.mcpserver.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.uml.BaseElement;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SafDataStore {

    private static final Logger LOG = Logger.getLogger(SafDataStore.class.getName());
    private static final long HOT_RELOAD_INTERVAL_MS = 2000;
    private static volatile SafDataStore instance;

    private final AtomicReference<DataIndex> currentIndex = new AtomicReference<>(null);
    private final String dataDir;
    private final Map<String, Long> fileCache = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread reloadThread;

    // ---- Records -----------------------------------------------------------

    public record SafViewpoint(
        String id, String name, String vpId, String maturity,
        String purpose, String applicability, String aspect, String domain,
        List<String> presentation, List<String> stakeholderIds,
        List<String> concernIds, List<String> recommendedVpIds,
        List<String> requiredVpIds, String exposure
    ) {}

    public record SafConcept(
        String id, String name, String classType, String documentation,
        List<String> inViewpointIds, List<String> inheritsFromIds,
        List<String> isGeneralForIds, List<ConceptAssociationEnd> associationEnds
    ) {}

    public record ConceptAssociationEnd(String conceptId, String conceptName, String multiplicity) {}

    public record SafConcern(String id, String name, String question, String category) {}

    public record SafStakeholder(String id, String name, String documentation) {}

    public record SafRationale(
        String id, String name, String documentation,
        String concernId, String concernName,
        String stakeholderId, String stakeholderName
    ) {}

    public record SafExpose(String conceptId, String conceptName, String viewpointId, String viewpointName) {}

    public record SafStereotype(String id, String name, String documentation) {}

    public record SafRealizeConcept(
        String id, String conceptId, String conceptName,
        String stereotypeId, String stereotypeName
    ) {}

    public record SafSpecialImplementation(
        String id, String relationType,
        String clientId, String clientName,
        String supplierId, String supplierName
    ) {}

    public record SafDomain(String id, String name, String domainId, String documentation) {}

    public record SafAspect(String id, String name, String aspectId, String documentation) {}

    /**
     * One candidate of a fuzzy domain/aspect lookup: the SAF code (domain
     * letter or aspect number), the canonical name, how well it scored, and
     * what matched ({@code code}, {@code name}, {@code snake_case},
     * {@code substring}, {@code tokens}, {@code spelling}).
     */
    public record SafVocabularyHit(String code, String name, double score, String via) {}

    /**
     * Result of resolving free-text input against the SAF domain/aspect
     * vocabulary. {@code canonical} is the SAF code of the best candidate
     * ({@code null} when nothing plausible was found); {@code exact} is true
     * when the input matched a code or name outright. {@code candidates} is
     * the ranked list, so a caller that used an unknown spelling can be told
     * what the plausible readings are instead of only getting a failure.
     */
    public record SafVocabularyMatch(
        String canonical, String canonicalName, boolean exact, List<SafVocabularyHit> candidates
    ) {}

    /** The grid cell a viewpoint belongs to, derived from its VP_ID. */
    public record SafGridCell(String domainCode, String domainName, String aspectId, String aspectName) {}

    // ---- DataIndex (immutable snapshot) -----------------------------------

    public static class DataIndex {

        private final Map<String, SafViewpoint> viewpointsById;
        private final Map<String, SafViewpoint> viewpointsByName;
        private final Map<String, SafViewpoint> viewpointsByVpId;

        private final Map<String, SafConcept> conceptsById;
        private final Map<String, SafConcept> conceptsByName;

        private final Map<String, SafConcern> concernsById;
        private final Map<String, SafConcern> concernsByTextHash;
        private final Map<String, SafConcern> concernsByName;

        private final Map<String, SafStakeholder> stakeholdersById;
        private final Map<String, SafStakeholder> stakeholdersByName;

        private final Map<String, SafStereotype> stereotypesById;
        private final Map<String, SafStereotype> stereotypesByName;

        private final List<SafRationale> rationales;
        private final List<SafExpose> exposes;
        private final List<SafRealizeConcept> realizeConcepts;
        private final List<SafSpecialImplementation> specialImplementations;

        private final List<SafDomain> domains;
        private final List<SafAspect> aspects;

        // Cross-reference indexes
        private final Map<String, List<SafViewpoint>> conceptToViewpoints;
        private final Map<String, List<SafConcept>> viewpointToConcepts;
        private final Map<String, List<SafConcern>> viewpointToConcerns;
        private final Map<String, List<SafViewpoint>> concernToViewpoints;
        private final Map<String, List<SafStakeholder>> concernToStakeholders;
        private final Map<String, List<SafRationale>> concernToRationales;
        private final Map<String, List<SafConcern>> stakeholderToConcerns;
        private final Map<String, List<SafRationale>> stakeholderToRationales;
        private final Map<String, List<SafStereotype>> conceptToDirectStereotypes;
        private final Map<String, List<SafStereotype>> conceptToIndirectStereotypes;
        private final Map<String, List<SafConcept>> stereotypeToDirectConcepts;
        private final Map<String, List<SafSpecialImplementation>> stereotypeToSpecialImpls;
        private final Map<String, List<SafConcept>> conceptParents;
        private final Map<String, List<SafConcept>> conceptChildren;

        DataIndex(
            Map<String, SafViewpoint> viewpointsById,
            Map<String, SafViewpoint> viewpointsByName,
            Map<String, SafViewpoint> viewpointsByVpId,
            Map<String, SafConcept> conceptsById,
            Map<String, SafConcept> conceptsByName,
            Map<String, SafConcern> concernsById,
            Map<String, SafConcern> concernsByTextHash,
            Map<String, SafConcern> concernsByName,
            Map<String, SafStakeholder> stakeholdersById,
            Map<String, SafStakeholder> stakeholdersByName,
            Map<String, SafStereotype> stereotypesById,
            Map<String, SafStereotype> stereotypesByName,
            List<SafRationale> rationales,
            List<SafExpose> exposes,
            List<SafRealizeConcept> realizeConcepts,
            List<SafSpecialImplementation> specialImplementations,
            List<SafDomain> domains,
            List<SafAspect> aspects,
            Map<String, List<SafViewpoint>> conceptToViewpoints,
            Map<String, List<SafConcept>> viewpointToConcepts,
            Map<String, List<SafConcern>> viewpointToConcerns,
            Map<String, List<SafViewpoint>> concernToViewpoints,
            Map<String, List<SafStakeholder>> concernToStakeholders,
            Map<String, List<SafRationale>> concernToRationales,
            Map<String, List<SafConcern>> stakeholderToConcerns,
            Map<String, List<SafRationale>> stakeholderToRationales,
            Map<String, List<SafStereotype>> conceptToDirectStereotypes,
            Map<String, List<SafStereotype>> conceptToIndirectStereotypes,
            Map<String, List<SafConcept>> stereotypeToDirectConcepts,
            Map<String, List<SafSpecialImplementation>> stereotypeToSpecialImpls,
            Map<String, List<SafConcept>> conceptParents,
            Map<String, List<SafConcept>> conceptChildren
        ) {
            this.viewpointsById = viewpointsById;
            this.viewpointsByName = viewpointsByName;
            this.viewpointsByVpId = viewpointsByVpId;
            this.conceptsById = conceptsById;
            this.conceptsByName = conceptsByName;
            this.concernsById = concernsById;
            this.concernsByTextHash = concernsByTextHash;
            this.concernsByName = concernsByName;
            this.stakeholdersById = stakeholdersById;
            this.stakeholdersByName = stakeholdersByName;
            this.stereotypesById = stereotypesById;
            this.stereotypesByName = stereotypesByName;
            this.rationales = rationales;
            this.exposes = exposes;
            this.realizeConcepts = realizeConcepts;
            this.specialImplementations = specialImplementations;
            this.domains = domains;
            this.aspects = aspects;
            this.conceptToViewpoints = conceptToViewpoints;
            this.viewpointToConcepts = viewpointToConcepts;
            this.viewpointToConcerns = viewpointToConcerns;
            this.concernToViewpoints = concernToViewpoints;
            this.concernToStakeholders = concernToStakeholders;
            this.concernToRationales = concernToRationales;
            this.stakeholderToConcerns = stakeholderToConcerns;
            this.stakeholderToRationales = stakeholderToRationales;
            this.conceptToDirectStereotypes = conceptToDirectStereotypes;
            this.conceptToIndirectStereotypes = conceptToIndirectStereotypes;
            this.stereotypeToDirectConcepts = stereotypeToDirectConcepts;
            this.stereotypeToSpecialImpls = stereotypeToSpecialImpls;
            this.conceptParents = conceptParents;
            this.conceptChildren = conceptChildren;
        }

        // -- Accessors --

        public Collection<SafViewpoint> allViewpoints() { return viewpointsById.values(); }
        public SafViewpoint getViewpointById(String id) { return viewpointsById.get(id); }
        public SafViewpoint getViewpointByName(String name) { return viewpointsByName.get(name.toLowerCase()); }
        public SafViewpoint getViewpointByVpId(String vpId) { return viewpointsByVpId.get(vpId.toLowerCase()); }
        public SafViewpoint getViewpoint(String nameOrId) {
            var vp = getViewpointByName(nameOrId);
            if (vp != null) return vp;
            vp = getViewpointByVpId(nameOrId);
            if (vp != null) return vp;
            return getViewpointById(nameOrId);
        }

        public Collection<SafConcept> allConcepts() { return conceptsById.values(); }
        public SafConcept getConceptById(String id) { return conceptsById.get(id); }
        public SafConcept getConceptByName(String name) { return conceptsByName.get(name.toLowerCase()); }
        public SafConcept getConcept(String nameOrId) {
            var c = getConceptByName(nameOrId);
            if (c != null) return c;
            return getConceptById(nameOrId);
        }

        public Collection<SafConcern> allConcerns() { return concernsById.values(); }
        public SafConcern getConcernById(String id) { return concernsById.get(id); }
        public SafConcern getConcernByTextHash(String hash) { return concernsByTextHash.get(hash); }

        public Collection<SafStakeholder> allStakeholders() { return stakeholdersById.values(); }
        public SafStakeholder getStakeholderById(String id) { return stakeholdersById.get(id); }
        public SafStakeholder getStakeholderByName(String name) { return stakeholdersByName.get(name.toLowerCase()); }

        public Collection<SafStereotype> allStereotypes() { return stereotypesById.values(); }
        public SafStereotype getStereotypeById(String id) { return stereotypesById.get(id); }
        public SafStereotype getStereotypeByName(String name) { return stereotypesByName.get(name.toLowerCase()); }

        public List<SafRationale> allRationales() { return rationales; }
        public List<SafExpose> allExposes() { return exposes; }
        public List<SafRealizeConcept> allRealizeConcepts() { return realizeConcepts; }
        public List<SafSpecialImplementation> allSpecialImplementations() { return specialImplementations; }
        public List<SafSpecialImplementation> getSpecialImplementationsForStereotype(String stereoName) {
            return stereotypeToSpecialImpls.getOrDefault(stereoName.toLowerCase(), List.of());
        }

        public List<SafDomain> allDomains() { return domains; }
        public List<SafAspect> allAspects() { return aspects; }

        // -- SAF grid vocabulary (domains.json / aspects.json) --------------
        //
        // A viewpoint's VP_ID encodes its grid cell: the leading letter is the
        // domain code and the following digit the aspect id, so "C1_SCXD" is
        // Conceptual x "Context & Exchange". That code is the authoritative
        // cell; the Domain/Aspect text fields are only a fallback.

        private record Vocab(String code, String name) {}

        /** Candidates scoring below this are not offered as readings. */
        private static final double VOCAB_FLOOR = 0.34;
        private static final int VOCAB_MAX_CANDIDATES = 5;

        /** The grid cell encoded in a VP_ID, or null when it does not parse. */
        public SafGridCell gridCell(String vpId) {
            if (vpId == null || vpId.length() < 2) {
                return null;
            }
            char letter = Character.toUpperCase(vpId.charAt(0));
            char digit = vpId.charAt(1);
            if (letter < 'A' || letter > 'Z' || digit < '1' || digit > '9') {
                return null;
            }
            var domain = domainByCode(String.valueOf(letter));
            var aspect = aspectById(String.valueOf(digit));
            if (domain == null || aspect == null) {
                return null;
            }
            return new SafGridCell(domain.domainId(), domain.name(), aspect.aspectId(), aspect.name());
        }

        public SafDomain domainByCode(String code) {
            if (code == null) {
                return null;
            }
            for (var d : domains) {
                if (d.domainId().equalsIgnoreCase(code.trim())) {
                    return d;
                }
            }
            return null;
        }

        public SafDomain domainByName(String name) {
            String n = norm(name);
            for (var d : domains) {
                if (norm(d.name()).equals(n) || d.name().replace(' ', '_').equalsIgnoreCase(n)) {
                    return d;
                }
            }
            return null;
        }

        public SafAspect aspectById(String aspectId) {
            if (aspectId == null) {
                return null;
            }
            for (var a : aspects) {
                if (a.aspectId().equals(aspectId.trim())) {
                    return a;
                }
            }
            return null;
        }

        public SafAspect aspectByName(String name) {
            String n = norm(name);
            for (var a : aspects) {
                if (norm(a.name()).equals(n)) {
                    return a;
                }
            }
            return null;
        }

        /** Domain of a viewpoint: from its VP_ID, else from its Domain field. */
        public SafDomain domainOf(SafViewpoint vp) {
            if (vp == null) {
                return null;
            }
            var cell = gridCell(vp.vpId());
            if (cell != null) {
                var d = domainByCode(cell.domainCode());
                if (d != null) {
                    return d;
                }
            }
            return domainByName(vp.domain());
        }

        /** Aspect of a viewpoint: from its VP_ID, else from its Aspect field. */
        public SafAspect aspectOf(SafViewpoint vp) {
            if (vp == null) {
                return null;
            }
            var cell = gridCell(vp.vpId());
            if (cell != null) {
                var a = aspectById(cell.aspectId());
                if (a != null) {
                    return a;
                }
            }
            return aspectByName(vp.aspect());
        }

        /** Viewpoints in a grid cell; a null code or aspect id means "any". */
        public List<SafViewpoint> viewpointsInCell(String domainCode, String aspectId) {
            var out = new ArrayList<SafViewpoint>();
            for (var vp : viewpointsById.values()) {
                // A viewpoint can ship without a VP_ID (one in the current SAF
                // data does), so resolve through the Domain/Aspect fields for
                // those instead of dropping them from the grid entirely.
                var d = domainOf(vp);
                var a = aspectOf(vp);
                if (d == null || a == null) {
                    continue;
                }
                if (domainCode != null && !d.domainId().equalsIgnoreCase(domainCode)) {
                    continue;
                }
                if (aspectId != null && !a.aspectId().equals(aspectId)) {
                    continue;
                }
                out.add(vp);
            }
            return out;
        }

        /**
         * Resolve free text against the domain vocabulary (domains.json),
         * ranking likely readings so a caller who used an unknown spelling
         * still gets the closest codes back.
         */
        public SafVocabularyMatch matchDomain(String query) {
            var vocab = new ArrayList<Vocab>();
            for (var d : domains) {
                vocab.add(new Vocab(d.domainId(), d.name()));
            }
            return matchVocabulary(query, vocab);
        }

        /** Resolve free text against the aspect vocabulary (aspects.json). */
        public SafVocabularyMatch matchAspect(String query) {
            var vocab = new ArrayList<Vocab>();
            for (var a : aspects) {
                vocab.add(new Vocab(a.aspectId(), a.name()));
            }
            return matchVocabulary(query, vocab);
        }

        private static SafVocabularyMatch matchVocabulary(String query, List<Vocab> vocab) {
            String q = norm(query);
            if (q.isEmpty()) {
                return new SafVocabularyMatch(null, null, false, List.of());
            }
            var scored = new ArrayList<SafVocabularyHit>();
            for (var v : vocab) {
                var hit = scoreVocab(q, v);
                if (hit != null) {
                    scored.add(hit);
                }
            }
            scored.sort(Comparator.comparingDouble(SafVocabularyHit::score).reversed());
            var top = List.copyOf(scored.subList(0, Math.min(VOCAB_MAX_CANDIDATES, scored.size())));
            SafVocabularyHit best = top.isEmpty() ? null : top.get(0);
            return new SafVocabularyMatch(
                best == null ? null : best.code(),
                best == null ? null : best.name(),
                best != null && best.score() >= 1.0,
                top);
        }

        /** Score one vocabulary term; null when it falls below the floor. */
        private static SafVocabularyHit scoreVocab(String q, Vocab v) {
            String code = norm(v.code());
            String name = norm(v.name());
            String snake = name.replace(' ', '_');
            String via;
            double score;
            if (q.equals(code)) {
                score = 1.0;
                via = "code";
            } else if (q.equals(name)) {
                score = 1.0;
                via = "name";
            } else if (q.equals(snake)) {
                score = 0.98;
                via = "snake_case";
            } else if (!name.isEmpty() && (name.contains(q) || q.contains(name))) {
                score = 0.9;
                via = "substring";
            } else {
                double tokens = dice(q, name);
                double spelling = 1.0 - (double) levenshtein(q, name)
                    / Math.max(1, Math.max(q.length(), name.length()));
                score = 0.85 * Math.max(tokens, spelling);
                via = "similar";
            }
            if (score < VOCAB_FLOOR) {
                return null;
            }
            return new SafVocabularyHit(v.code(), v.name(), Math.round(score * 1000.0) / 1000.0, via);
        }

        /** Sørensen–Dice coefficient over word tokens. */
        private static double dice(String a, String b) {
            var ta = new HashSet<>(List.of(a.split(" ")));
            var tb = new HashSet<>(List.of(b.split(" ")));
            ta.remove("");
            tb.remove("");
            if (ta.isEmpty() || tb.isEmpty()) {
                return 0.0;
            }
            var inter = new HashSet<>(ta);
            inter.retainAll(tb);
            return 2.0 * inter.size() / (ta.size() + tb.size());
        }

        private static int levenshtein(String a, String b) {
            int[] prev = new int[b.length() + 1];
            int[] cur = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) {
                prev[j] = j;
            }
            for (int i = 1; i <= a.length(); i++) {
                cur[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                int[] swap = prev;
                prev = cur;
                cur = swap;
            }
            return prev[b.length()];
        }

        /**
         * Lower-case, collapse every non-alphanumeric run to a single space and
         * drop the connectors, so "Context & Exchange", "context_and_exchange"
         * and "context exchange" all normalize alike.
         */
        private static String norm(String s) {
            if (s == null) {
                return "";
            }
            var words = new ArrayList<String>();
            for (var w : s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
                if (!w.isEmpty() && !w.equals("and") && !w.equals("or")) {
                    words.add(w);
                }
            }
            return String.join(" ", words);
        }

        // -- Cross-references --

        public List<SafViewpoint> getViewpointsForConcept(String conceptId) {
            return conceptToViewpoints.getOrDefault(conceptId, List.of());
        }

        public List<SafConcept> getConceptsForViewpoint(String viewpointId) {
            return viewpointToConcepts.getOrDefault(viewpointId, List.of());
        }

        public List<SafConcern> getConcernsForViewpoint(String viewpointId) {
            return viewpointToConcerns.getOrDefault(viewpointId, List.of());
        }

        public List<SafViewpoint> getViewpointsForConcern(String concernId) {
            return concernToViewpoints.getOrDefault(concernId, List.of());
        }

        public List<SafStakeholder> getStakeholdersForConcern(String concernId) {
            return concernToStakeholders.getOrDefault(concernId, List.of());
        }

        public List<SafRationale> getRationalesForConcern(String concernId) {
            return concernToRationales.getOrDefault(concernId, List.of());
        }

        public List<SafConcern> getConcernsForStakeholder(String stakeholderId) {
            return stakeholderToConcerns.getOrDefault(stakeholderId, List.of());
        }

        public List<SafRationale> getRationalesForStakeholder(String stakeholderId) {
            return stakeholderToRationales.getOrDefault(stakeholderId, List.of());
        }

        public List<SafStereotype> getDirectStereotypesForConcept(String conceptId) {
            return conceptToDirectStereotypes.getOrDefault(conceptId, List.of());
        }

        public List<SafStereotype> getIndirectStereotypesForConcept(String conceptId) {
            return conceptToIndirectStereotypes.getOrDefault(conceptId, List.of());
        }

        public List<SafStereotype> getAllStereotypesForConcept(String conceptId) {
            var result = new ArrayList<>(getDirectStereotypesForConcept(conceptId));
            result.addAll(getIndirectStereotypesForConcept(conceptId));
            return result;
        }

        public List<SafConcept> getConceptsForStereotype(String stereotypeId) {
            return stereotypeToDirectConcepts.getOrDefault(stereotypeId, List.of());
        }

        public List<SafConcept> getConceptParents(String conceptId) {
            return conceptParents.getOrDefault(conceptId, List.of());
        }

        public List<SafConcept> getConceptChildren(String conceptId) {
            return conceptChildren.getOrDefault(conceptId, List.of());
        }

        // -- Search --

        public List<SearchResult> search(String query, String typeFilter) {
            var q = query.toLowerCase();
            var results = new ArrayList<SearchResult>();

            boolean allTypes = typeFilter == null || typeFilter.isEmpty();

            if (allTypes || "viewpoint".equalsIgnoreCase(typeFilter)) {
                for (var vp : viewpointsById.values()) {
                    if (vp.name().toLowerCase().contains(q) || vp.vpId().toLowerCase().contains(q)) {
                        results.add(new SearchResult("viewpoint", vp.name(), vp.id(), vp.vpId()));
                    }
                }
            }

            if (allTypes || "concept".equalsIgnoreCase(typeFilter)) {
                for (var c : conceptsById.values()) {
                    if (c.name().toLowerCase().contains(q)) {
                        results.add(new SearchResult("concept", c.name(), c.id(), c.classType()));
                    }
                }
            }

            if (allTypes || "concern".equalsIgnoreCase(typeFilter)) {
                for (var c : concernsById.values()) {
                    if (c.question().toLowerCase().contains(q) || c.name().toLowerCase().contains(q)) {
                        results.add(new SearchResult("concern", truncate(c.question(), 100), c.id(), c.category()));
                    }
                }
            }

            if (allTypes || "stakeholder".equalsIgnoreCase(typeFilter)) {
                for (var s : stakeholdersById.values()) {
                    if (s.name().toLowerCase().contains(q)) {
                        results.add(new SearchResult("stakeholder", s.name(), s.id(), ""));
                    }
                }
            }

            if (allTypes || "stereotype".equalsIgnoreCase(typeFilter)) {
                for (var s : stereotypesById.values()) {
                    if (s.name().toLowerCase().contains(q)) {
                        results.add(new SearchResult("stereotype", s.name(), s.id(), ""));
                    }
                }
            }

            results.sort(Comparator.comparingInt(r -> r.name().length()));
            return results;
        }

        private static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }

    public record SearchResult(String type, String name, String id, String subtext) {}

    // ---- Singleton ---------------------------------------------------------

    public static SafDataStore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SafDataStore not initialized. Call init(dataDir) first.");
        }
        return instance;
    }

    public static synchronized SafDataStore init(String dataDir) {
        if (instance != null) {
            instance.stop();
        }
        instance = new SafDataStore(dataDir);
        var idx = instance.loadData();
        instance.currentIndex.set(idx);
        LOG.info("SafDataStore loaded " + idx.viewpointsById.size() + " viewpoints, "
            + idx.conceptsById.size() + " concepts, "
            + idx.concernsById.size() + " concerns, "
            + idx.stakeholdersById.size() + " stakeholders, "
            + idx.stereotypesById.size() + " stereotypes");
        instance.startHotReload();
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    private SafDataStore(String dataDir) {
        this.dataDir = dataDir;
    }

    public DataIndex getCurrentIndex() {
        return currentIndex.get();
    }

    // ---- Cameo spec model query -------------------------------------------

    public BaseElement getCameoElement(String safGuid) {
        if (safGuid == null || safGuid.isEmpty()) return null;
        var proj = Application.getInstance().getProject();
        if (proj == null) return null;
        return proj.getElementByID(safGuid);
    }

    // ---- Hot-reload --------------------------------------------------------

    private void startHotReload() {
        running = true;
        reloadThread = new Thread(this::hotReloadLoop, "saf-data-hot-reload");
        reloadThread.setDaemon(true);
        reloadThread.start();
    }

    private void stop() {
        running = false;
        if (reloadThread != null && reloadThread.isAlive()) {
            reloadThread.interrupt();
            try {
                reloadThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void hotReloadLoop() {
        while (running) {
            try {
                if (hasChanges()) {
                    LOG.info("SafDataStore: data files changed, reloading...");
                    var idx = loadData();
                    if (idx != null) {
                        currentIndex.set(idx);
                        LOG.info("SafDataStore: reloaded "
                            + idx.viewpointsById.size() + " viewpoints, "
                            + idx.conceptsById.size() + " concepts");
                    }
                }
                Thread.sleep(HOT_RELOAD_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("SafDataStore hot-reload error: " + e.getMessage());
            }
        }
    }

    private boolean hasChanges() {
        var dir = new File(dataDir);
        if (!dir.isDirectory()) return false;
        var files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return false;
        for (var f : files) {
            var cached = fileCache.get(f.getAbsolutePath());
            if (cached == null || cached != f.lastModified()) {
                return true;
            }
        }
        return false;
    }

    // ---- Data loading ------------------------------------------------------

    private DataIndex loadData() {
        var mapper = new ObjectMapper();
        var dir = new File(dataDir);
        if (!dir.isDirectory()) {
            LOG.warning("SafDataStore: data directory not found: " + dataDir);
            return null;
        }

        // Update file cache
        var newCache = new ConcurrentHashMap<String, Long>();
        var jsonFiles = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (jsonFiles != null) {
            for (var f : jsonFiles) {
                newCache.put(f.getAbsolutePath(), f.lastModified());
            }
        }
        fileCache.clear();
        fileCache.putAll(newCache);

        try {
            // Load all JSON
            var vpNodes = readJsonArray(mapper, dir, "viewpoints.json");
            var conceptNodes = readJsonArray(mapper, dir, "concepts.json");
            var concernNodes = readJsonArray(mapper, dir, "concerns.json");
            var stakeholderNodes = readJsonArray(mapper, dir, "stakeholders.json");
            var rationaleNodes = readJsonArray(mapper, dir, "rationales.json");
            var exposeNodes = readJsonArray(mapper, dir, "exposes.json");
            var stereotypeNodes = readJsonArray(mapper, dir, "stereotypes.json");
            var sysmlStereotypeNodes = readJsonArray(mapper, dir, "sysmlstereotypes.json");
            var umlMetaclassNodes = readJsonArray(mapper, dir, "umlmetaclasses.json");
            var realizeNodes = readJsonArray(mapper, dir, "realizeconcept.json");
            var specialImplNodes = readJsonArray(mapper, dir, "special-implementations.json");
            var domainNodes = readJsonArray(mapper, dir, "domains.json");
            var aspectNodes = readJsonArray(mapper, dir, "aspects.json");

            // Parse records
            var viewpoints = parseViewpoints(vpNodes);
            var concepts = parseConcepts(conceptNodes);
            var concerns = parseConcerns(concernNodes);
            var stakeholders = parseStakeholders(stakeholderNodes);
            var rationales = parseRationales(rationaleNodes);
            var exposes = parseExposes(exposeNodes);
            var stereotypes = parseStereotypes(stereotypeNodes);
            stereotypes.putAll(parseStereotypes(sysmlStereotypeNodes));
            stereotypes.putAll(parseStereotypes(umlMetaclassNodes));
            var realizeConcepts = parseRealizeConcepts(realizeNodes);
            var specialImpls = parseSpecialImplementations(specialImplNodes);
            var domains = parseDomains(domainNodes);
            var aspects = parseAspects(aspectNodes);

            // Build indexes
            return buildIndex(viewpoints, concepts, concerns, stakeholders,
                rationales, exposes, stereotypes, realizeConcepts, specialImpls,
                domains, aspects);

        } catch (Exception e) {
            LOG.severe("SafDataStore: failed to load data: " + e.getMessage());
            e.printStackTrace();
            return currentIndex.get(); // keep old index on failure
        }
    }

    private JsonNode readJsonArray(ObjectMapper mapper, File dir, String filename) throws IOException {
        var file = new File(dir, filename);
        if (!file.exists()) {
            LOG.warning("SafDataStore: " + filename + " not found, using empty array");
            return mapper.createArrayNode();
        }
        return mapper.readTree(file);
    }

    // ---- Parsers ------------ ---------------------------------------------

    private Map<String, SafViewpoint> parseViewpoints(JsonNode nodes) {
        var map = new LinkedHashMap<String, SafViewpoint>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var vp = new SafViewpoint(
                id,
                text(n, "Name", ""),
                text(n, "VP_ID", ""),
                text(n, "Maturity", ""),
                text(n, "Purpose", ""),
                text(n, "Applicability", ""),
                text(n, "Aspect", ""),
                text(n, "Domain", "").toLowerCase().replaceAll(" ", "_"),
                strings(n, "Presentation"),
                refIds(n, "Stakeholders"),
                refIds(n, "Concern"),
                refIds(n, "RecommendedVP"),
                refIds(n, "RequiredVP"),
                text(n, "Exposure", "")
            );
            map.put(id, vp);
        }
        return map;
    }

    private Map<String, SafConcept> parseConcepts(JsonNode nodes) {
        var map = new LinkedHashMap<String, SafConcept>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var ends = new ArrayList<ConceptAssociationEnd>();
            var ae = n.get("AssociationEnds");
            if (ae != null) {
                for (var e : ae) {
                    var ref = e.get("ID");
                    ends.add(new ConceptAssociationEnd(
                        text(e, "ID", ""),
                        text(e, "Name", ""),
                        text(e, "Multiplicity", "")
                    ));
                }
            }
            var c = new SafConcept(
                id,
                text(n, "Name", ""),
                text(n, "ClassType", ""),
                text(n, "Documentation", ""),
                refIds(n, "InViewpoint"),
                refIdsFromArray(n, "InheritsFrom"),
                refIdsFromArray(n, "IsGeneralFor"),
                ends
            );
            map.put(id, c);
        }
        return map;
    }

    private Map<String, SafConcern> parseConcerns(JsonNode nodes) {
        var map = new LinkedHashMap<String, SafConcern>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var name = text(n, "Name", "");
            var c = new SafConcern(
                id,
                makeConcernName(name),
                name,
                text(n, "Owner", "")
            );
            map.put(id, c);
        }
        return map;
    }

    private static String makeConcernName(String question) {
        if (question == null || question.isEmpty()) return "unknown_concern";
        var shortName = question.length() > 60 ? question.substring(0, 57) + "..." : question;
        return shortName;
    }

    private Map<String, SafStakeholder> parseStakeholders(JsonNode nodes) {
        var map = new LinkedHashMap<String, SafStakeholder>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            map.put(id, new SafStakeholder(id, text(n, "Name", ""), text(n, "Documentation", "")));
        }
        return map;
    }

    private List<SafRationale> parseRationales(JsonNode nodes) {
        var list = new ArrayList<SafRationale>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var concern = n.get("Concern");
            var stakeholder = n.get("Stakeholder");
            list.add(new SafRationale(
                id, text(n, "Name", ""), text(n, "Documentation", ""),
                concern != null ? text(concern, "ID", "") : "",
                concern != null ? text(concern, "Name", "") : "",
                stakeholder != null ? text(stakeholder, "ID", "") : "",
                stakeholder != null ? text(stakeholder, "Name", "") : ""
            ));
        }
        return list;
    }

    private List<SafExpose> parseExposes(JsonNode nodes) {
        var list = new ArrayList<SafExpose>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var concept = n.get("ExposedConcept");
            var viewpoint = n.get("Viewpoint");
            list.add(new SafExpose(
                concept != null ? text(concept, "ID", "") : "",
                concept != null ? text(concept, "Name", "") : "",
                viewpoint != null ? text(viewpoint, "ID", "") : "",
                viewpoint != null ? text(viewpoint, "Name", "") : ""
            ));
        }
        return list;
    }

    private Map<String, SafStereotype> parseStereotypes(JsonNode nodes) {
        var map = new LinkedHashMap<String, SafStereotype>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            map.put(id, new SafStereotype(id, text(n, "Name", ""), text(n, "Documentation", "")));
        }
        return map;
    }

    private List<SafRealizeConcept> parseRealizeConcepts(JsonNode nodes) {
        var list = new ArrayList<SafRealizeConcept>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var rc = n.get("RealizedConcept");
            var roc = n.get("RealizationOfConcept");
            list.add(new SafRealizeConcept(
                id,
                rc != null ? text(rc, "ID", "") : "",
                rc != null ? text(rc, "Name", "") : "",
                roc != null ? text(roc, "ID", "") : "",
                roc != null ? text(roc, "Name", "") : ""
            ));
        }
        return list;
    }

    private List<SafSpecialImplementation> parseSpecialImplementations(JsonNode nodes) {
        var list = new ArrayList<SafSpecialImplementation>();
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            var client = n.get("Client");
            var supplier = n.get("Supplier");
            list.add(new SafSpecialImplementation(
                id,
                text(n, "Stereotype", ""),
                client != null ? text(client, "ID", "") : "",
                client != null ? text(client, "Name", "") : "",
                supplier != null ? text(supplier, "ID", "") : "",
                supplier != null ? text(supplier, "Name", "") : ""
            ));
        }
        return list;
    }

    private List<SafDomain> parseDomains(JsonNode nodes) {
        var list = new ArrayList<SafDomain>();
        if (nodes == null) return list;
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            list.add(new SafDomain(id, text(n, "Name", ""), text(n, "DomainID", ""), text(n, "Documentation", "")));
        }
        return list;
    }

    private List<SafAspect> parseAspects(JsonNode nodes) {
        var list = new ArrayList<SafAspect>();
        if (nodes == null) return list;
        for (var n : nodes) {
            var id = text(n, "ID");
            if (id == null) continue;
            list.add(new SafAspect(id, text(n, "Name", ""), text(n, "AspectID", ""), text(n, "Documentation", "")));
        }
        return list;
    }

    // ---- Index builder -----------------------------------------------------

    private DataIndex buildIndex(
        Map<String, SafViewpoint> vpMap,
        Map<String, SafConcept> conceptMap,
        Map<String, SafConcern> concernMap,
        Map<String, SafStakeholder> stakeholderMap,
        List<SafRationale> rationales,
        List<SafExpose> exposes,
        Map<String, SafStereotype> stereotypeMap,
        List<SafRealizeConcept> realizeConcepts,
        List<SafSpecialImplementation> specialImpls,
        List<SafDomain> domains,
        List<SafAspect> aspects
    ) {
        // -- viewpoint by name and vpId --
        var vpByName = new LinkedHashMap<String, SafViewpoint>();
        var vpByVpId = new LinkedHashMap<String, SafViewpoint>();
        for (var vp : vpMap.values()) {
            var lowerName = vp.name().toLowerCase();
            if (!lowerName.isEmpty()) vpByName.put(lowerName, vp);
            var lowerVpId = vp.vpId().toLowerCase();
            if (!lowerVpId.isEmpty()) vpByVpId.put(lowerVpId, vp);
        }

        // -- concept by name --
        var conceptByName = new LinkedHashMap<String, SafConcept>();
        for (var c : conceptMap.values()) {
            var lower = c.name().toLowerCase();
            if (!lower.isEmpty()) conceptByName.put(lower, c);
        }

        // -- concern by name and text hash --
        var concernByName = new LinkedHashMap<String, SafConcern>();
        var concernByHash = new LinkedHashMap<String, SafConcern>();
        for (var c : concernMap.values()) {
            var lower = c.name().toLowerCase();
            if (!lower.isEmpty()) concernByName.put(lower, c);
            var hash = sha256(c.question());
            if (hash != null) concernByHash.put(hash, c);
        }

        // -- stakeholder by name --
        var stakeholderByName = new LinkedHashMap<String, SafStakeholder>();
        for (var s : stakeholderMap.values()) {
            var lower = s.name().toLowerCase();
            if (!lower.isEmpty()) stakeholderByName.put(lower, s);
        }

        // -- stereotype by name --
        var stereotypeByName = new LinkedHashMap<String, SafStereotype>();
        for (var s : stereotypeMap.values()) {
            var lower = s.name().toLowerCase();
            if (!lower.isEmpty()) stereotypeByName.put(lower, s);
        }

        // -- Expose -> concept <-> viewpoint cross-refs --
        var conceptToVp = new LinkedHashMap<String, List<SafViewpoint>>();
        var vpToConcept = new LinkedHashMap<String, List<SafConcept>>();
        for (var ex : exposes) {
            // concept -> viewpoint
            conceptToVp.computeIfAbsent(ex.conceptId(), k -> new ArrayList<>());
            var vp = vpMap.get(ex.viewpointId());
            if (vp != null) conceptToVp.get(ex.conceptId()).add(vp);
            // viewpoint -> concept
            vpToConcept.computeIfAbsent(ex.viewpointId(), k -> new ArrayList<>());
            var c = conceptMap.get(ex.conceptId());
            if (c != null) vpToConcept.get(ex.viewpointId()).add(c);
        }

        // -- viewpoint <-> concern cross-refs --
        var vpToConcern = new LinkedHashMap<String, List<SafConcern>>();
        var concernToVp = new LinkedHashMap<String, List<SafViewpoint>>();
        for (var vp : vpMap.values()) {
            var concerns = new ArrayList<SafConcern>();
            for (var cid : vp.concernIds()) {
                var c = concernMap.get(cid);
                if (c != null) {
                    concerns.add(c);
                    concernToVp.computeIfAbsent(cid, k -> new ArrayList<>()).add(vp);
                }
            }
            if (!concerns.isEmpty()) vpToConcern.put(vp.id(), concerns);
        }

        // -- stakeholder <-> concern via rationales --
        var concernToStakeholder = new LinkedHashMap<String, List<SafStakeholder>>();
        var concernToRationale = new LinkedHashMap<String, List<SafRationale>>();
        var stakeholderToConcern = new LinkedHashMap<String, List<SafConcern>>();
        var stakeholderToRationale = new LinkedHashMap<String, List<SafRationale>>();
        for (var r : rationales) {
            if (!r.concernId().isEmpty()) {
                concernToRationale.computeIfAbsent(r.concernId(), k -> new ArrayList<>()).add(r);
                var sh = stakeholderMap.get(r.stakeholderId());
                if (sh != null) {
                    concernToStakeholder.computeIfAbsent(r.concernId(), k -> new ArrayList<>()).add(sh);
                }
            }
            if (!r.stakeholderId().isEmpty()) {
                stakeholderToRationale.computeIfAbsent(r.stakeholderId(), k -> new ArrayList<>()).add(r);
                var c = concernMap.get(r.concernId());
                if (c != null) {
                    stakeholderToConcern.computeIfAbsent(r.stakeholderId(), k -> new ArrayList<>()).add(c);
                }
            }
        }

        // -- realizeconcept: concept <-> stereotype --
        var conceptToDirectStereo = new LinkedHashMap<String, List<SafStereotype>>();
        var stereoToDirectConcept = new LinkedHashMap<String, List<SafConcept>>();
        for (var rc : realizeConcepts) {
            var stereo = stereotypeMap.get(rc.stereotypeId());
            if (stereo != null) {
                conceptToDirectStereo.computeIfAbsent(rc.conceptId(), k -> new ArrayList<>()).add(stereo);
            }
            var c = conceptMap.get(rc.conceptId());
            if (c != null) {
                stereoToDirectConcept.computeIfAbsent(rc.stereotypeId(), k -> new ArrayList<>()).add(c);
            }
        }

        // -- special-implementations: indirect stereotype mapping --
        var conceptToIndirectStereo = new LinkedHashMap<String, List<SafStereotype>>();
        var stereoToSpecialImpl = new LinkedHashMap<String, List<SafSpecialImplementation>>();
        for (var si : specialImpls) {
            stereoToSpecialImpl.computeIfAbsent(si.supplierName().toLowerCase(), k -> new ArrayList<>()).add(si);
            // Map from concept ClassType to indirect stereotype
            // For each concept whose ClassType matches the Client UML metaclass
            for (var c : conceptMap.values()) {
                if (c.classType().equalsIgnoreCase(si.clientName())) {
                    var stereo = stereotypeMap.get(si.supplierId());
                    if (stereo != null) {
                        conceptToIndirectStereo.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(stereo);
                    }
                }
            }
        }

        // -- concept inheritance (parents and children) --
        var conceptParents = new LinkedHashMap<String, List<SafConcept>>();
        var conceptChildren = new LinkedHashMap<String, List<SafConcept>>();
        for (var c : conceptMap.values()) {
            for (var pid : c.inheritsFromIds()) {
                var parent = conceptMap.get(pid);
                if (parent != null) {
                    conceptParents.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(parent);
                    conceptChildren.computeIfAbsent(pid, k -> new ArrayList<>()).add(c);
                }
            }
        }

        // Make everything unmodifiable
        return new DataIndex(
            Map.copyOf(vpMap),
            Map.copyOf(vpByName),
            Map.copyOf(vpByVpId),
            Map.copyOf(conceptMap),
            Map.copyOf(conceptByName),
            Map.copyOf(concernMap),
            Map.copyOf(concernByHash),
            Map.copyOf(concernByName),
            Map.copyOf(stakeholderMap),
            Map.copyOf(stakeholderByName),
            Map.copyOf(stereotypeMap),
            Map.copyOf(stereotypeByName),
            List.copyOf(rationales),
            List.copyOf(exposes),
            List.copyOf(realizeConcepts),
            List.copyOf(specialImpls),
            List.copyOf(domains),
            List.copyOf(aspects),
            freezeMapOfLists(conceptToVp),
            freezeMapOfLists(vpToConcept),
            freezeMapOfLists(vpToConcern),
            freezeMapOfLists(concernToVp),
            freezeMapOfLists(concernToStakeholder),
            freezeMapOfLists(concernToRationale),
            freezeMapOfLists(stakeholderToConcern),
            freezeMapOfLists(stakeholderToRationale),
            freezeMapOfLists(conceptToDirectStereo),
            freezeMapOfLists(conceptToIndirectStereo),
            freezeMapOfLists(stereoToDirectConcept),
            freezeMapOfLists(stereoToSpecialImpl),
            freezeMapOfLists(conceptParents),
            freezeMapOfLists(conceptChildren)
        );
    }

    // ---- Helpers -----------------------------------------------------------

    private static String text(JsonNode n, String field) {
        var v = n.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String text(JsonNode n, String field, String def) {
        var v = text(n, field);
        return v != null ? v : def;
    }

    private static List<String> strings(JsonNode n, String field) {
        var arr = n.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var v : arr) {
            if (!v.isNull()) result.add(v.asText());
        }
        return result;
    }

    private static List<String> refIds(JsonNode n, String field) {
        var arr = n.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var v : arr) {
            if (v.isTextual()) {
                result.add(v.asText());
            } else {
                var id = v.get("ID");
                if (id != null && !id.isNull()) result.add(id.asText());
            }
        }
        return result;
    }

    private static List<String> refIdsFromArray(JsonNode n, String field) {
        var arr = n.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (var v : arr) {
            if (v.isTextual()) {
                result.add(v.asText());
            } else {
                var id = v.get("ID");
                if (id != null && !id.isNull()) result.add(id.asText());
            }
        }
        return result;
    }

    private static <K, V> Map<K, List<V>> freezeMapOfLists(Map<K, List<V>> map) {
        var frozen = new LinkedHashMap<K, List<V>>();
        for (var entry : map.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static String sha256(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (var b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
