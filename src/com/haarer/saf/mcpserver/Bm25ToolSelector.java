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
     * Choose the tools to present for the given user message.
     *
     * @param message   the user's message, analyzed into query terms
     * @param threshold minimum BM25 score a tool needs to be presented
     * @param max       maximum number of tools returned, best first
     * @return matching tools in descending score order; empty when nothing
     *         matches or the message carries no query terms
     */
    public List<LlmChatClient.Tool> select(String message, double threshold, int max) {
        if (message == null || message.isBlank() || max <= 0 || current.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = index.search(message, max, Retriever.MatchPolicy.OR);
        List<LlmChatClient.Tool> selected = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            if (hit.score() < threshold) {
                break; // hits arrive in descending score order
            }
            LlmChatClient.Tool tool = current.get(hit.id());
            if (tool != null) {
                selected.add(tool);
            }
        }
        return List.copyOf(selected);
    }
}
