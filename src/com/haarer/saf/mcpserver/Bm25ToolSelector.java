package com.haarer.saf.mcpserver;

import com.haarer.saf.mcpserver.retrieval.Bm25Retriever;
import com.haarer.saf.mcpserver.retrieval.Hit;
import com.haarer.saf.mcpserver.retrieval.IndexedDoc;
import com.haarer.saf.mcpserver.retrieval.Retriever;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25-based selection of which of the registered {@link LlmChatClient.Tool}s
 * are presented to the LLM model for a user turn.
 *
 * <p>The index carries one document per tool with two fields: {@code name}
 * (boost {@value #NAME_BOOST}) and {@code description} (boost 1.0). The name
 * boost matters because users reference tools by name or name fragments
 * ("export the diagram" &rarr; {@code export_diagram_as_png}); the
 * {@code StandardAnalyzer} tokenizes on non-word characters, so
 * {@code export_diagram_as_png} indexes as the tokens {@code export},
 * {@code diagram}, {@code as}, {@code png}.
 *
 * <p>{@link #select} searches with {@link Retriever.MatchPolicy#OR}: any
 * query term matching a tool is a signal, and BM25 ranks tools matching more
 * (and rarer) terms higher, so the {@code llm.tool.threshold} score filter is
 * what prunes noise rather than a term-count cutoff. A message sharing no
 * tokens with any tool yields an empty result; the caller falls back to
 * presenting all tools.
 *
 * <p>{@link #select} reports the evidence, not just the winners: every
 * candidate carries its raw BM25 score and a confidence relative to the best
 * match of the same call, and the dropped tools are reported alongside the
 * kept ones so a thin or wrong selection is visible rather than silent.
 *
 * <p>Not thread-safe: rebuild and select from the single console worker
 * thread.
 */
public final class Bm25ToolSelector {

    /** Boost of the tool-name field over the description field. */
    static final float NAME_BOOST = 3.0f;

    private final Bm25Retriever index;
    private final Map<String, LlmChatClient.Tool> current = new LinkedHashMap<>();

    public Bm25ToolSelector() throws IOException {
        this.index = new Bm25Retriever(Map.of("name", NAME_BOOST, "description", 1.0f));
    }

    /**
     * Replace the indexed tool set. Cheap (in-memory, tens of documents);
     * call after the registration changes and before the next
     * {@link #select}.
     *
     * @throws IOException if the underlying index cannot be committed
     */
    public void rebuild(Collection<LlmChatClient.Tool> tools) throws IOException {
        List<IndexedDoc> docs = new ArrayList<>(tools.size());
        for (LlmChatClient.Tool tool : tools) {
            docs.add(new IndexedDoc(tool.name(), Map.of(
                "name", tool.name(),
                "description", tool.description() == null ? "" : tool.description())));
        }
        index.replace(docs);
        current.clear();
        for (LlmChatClient.Tool tool : tools) {
            current.put(tool.name(), tool);
        }
    }

    /**
     * One tool with the evidence behind its selection. {@code score} is the raw
     * BM25 score (unbounded, not a probability); {@code confidence} is that
     * score relative to the best-scoring tool of the same turn, so
     * {@code 1.0} is the top match and {@code band} gives a coarse read
     * (high &ge; 0.66, medium &ge; 0.33, low below).
     */
    public record Scored(String name, double score, double confidence, String band) {}

    /**
     * Outcome of one turn's tool selection. {@code mode} is {@code bm25} when
     * the query actually narrowed the set, otherwise {@code all} with a
     * {@code reason} saying why every tool was presented instead;
     * {@code selected} and {@code rejected} always carry the confidence, so a
     * report can show what was dropped as well as what was kept.
     */
    public record Selection(String mode, String reason, int totalTools,
                            List<Scored> selected, List<Scored> rejected) {

        public boolean narrowed() {
            return "bm25".equals(mode);
        }
    }

    /** How many rejected tools a report lists before truncating. */
    private static final int MAX_REPORTED_REJECTED = 10;

    /**
     * Choose the tools to present for the given user message, keeping the
     * scores so the caller can report confidence.
     *
     * @param message   the user's message, analyzed into query terms
     * @param threshold minimum BM25 score a tool needs to be presented
     * @param max       maximum number of tools returned, best first
     * @return the selection with the evidence; {@code mode} is {@code all}
     *         with a reason when nothing matched or the message is empty
     */
    public Selection select(String message, double threshold, int max) {
        if (message == null || message.isBlank() || max <= 0 || current.isEmpty()) {
            return new Selection("all", "no query terms to match tools on", current.size(), List.of(), List.of());
        }
        // Ask for more than we may keep, so the rejects can be reported too.
        int wanted = Math.min(current.size(), max + MAX_REPORTED_REJECTED);
        List<Hit> hits = index.search(message, wanted, Retriever.MatchPolicy.OR);
        if (hits.isEmpty()) {
            return new Selection("all", "no tool matched the message", current.size(), List.of(), List.of());
        }
        double best = hits.get(0).score();
        var selected = new ArrayList<Scored>();
        var rejected = new ArrayList<Scored>();
        for (Hit hit : hits) {
            LlmChatClient.Tool tool = current.get(hit.id());
            if (tool == null) {
                continue;
            }
            double confidence = best > 0 ? hit.score() / best : 0.0;
            var scored = new Scored(tool.name(), round(hit.score()), round(confidence), band(confidence));
            boolean keep = selected.size() < max && hit.score() >= threshold;
            if (keep) {
                selected.add(scored);
            } else if (rejected.size() < MAX_REPORTED_REJECTED) {
                rejected.add(scored);
            }
        }
        if (selected.isEmpty()) {
            return new Selection("all",
                "nothing scored at or above llm.tool.threshold " + threshold, current.size(), List.of(), rejected);
        }
        return new Selection("bm25", "", current.size(), List.copyOf(selected), List.copyOf(rejected));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String band(double confidence) {
        if (confidence >= 0.66) {
            return "high";
        }
        return confidence >= 0.33 ? "medium" : "low";
    }

}
