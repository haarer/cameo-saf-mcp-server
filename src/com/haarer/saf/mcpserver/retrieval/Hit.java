package com.haarer.saf.mcpserver.retrieval;

/**
 * One retrieval result: the indexed document id and its BM25 score
 * (higher = better match). Scores are only comparable within a single search.
 */
public record Hit(String id, float score) {
}
