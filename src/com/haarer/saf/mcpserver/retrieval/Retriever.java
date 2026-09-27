package com.haarer.saf.mcpserver.retrieval;

import java.util.List;

/**
 * Read side of a text retrieval index. Implementations must be safe for
 * concurrent {@link #search} calls while another thread mutates the index.
 *
 * <p>Planned consumers of this seam: MCP tool discovery, compacted
 * conversation retrieval, and open-model content search. A future
 * embedding-based hybrid can implement the same interface without touching
 * call sites.
 */
public interface Retriever {

    /** How query terms combine. */
    enum MatchPolicy {
        /** A document must contain every query term. */
        AND,
        /** A document matching any query term is a hit; the score reflects
         *  all matching terms, so documents matching more (rarer) terms
         *  rank higher. */
        OR
    }

    /**
     * Search the index.
     *
     * @param query  natural-language query, analyzed the same way indexed text is
     * @param k      maximum number of hits, best first; {@code k <= 0} returns none
     * @param policy how query terms combine (see {@link MatchPolicy})
     * @return up to {@code k} hits in descending score order; empty when nothing matches
     */
    List<Hit> search(String query, int k, MatchPolicy policy);
}
