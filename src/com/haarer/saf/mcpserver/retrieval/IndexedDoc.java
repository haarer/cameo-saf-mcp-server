package com.haarer.saf.mcpserver.retrieval;

import java.util.Map;

/**
 * A document to index: a stable id plus named text fields. Field names
 * matter because query boosts are per-field (e.g. {@code name} vs
 * {@code text}); every value is analyzed (tokenized, lowercased) on write.
 *
 * @param id     stable document identifier; upserts and removals key on it
 * @param fields indexed text fields (field name -> text); must not be empty
 */
public record IndexedDoc(String id, Map<String, String> fields) {

    /** Convenience: a single {@code text} field. */
    public IndexedDoc(String id, String text) {
        this(id, Map.of("text", text == null ? "" : text));
    }

    /** Convenience: a boosted-identifier {@code name} field plus a {@code text} field. */
    public static IndexedDoc of(String id, String name, String text) {
        return new IndexedDoc(id, Map.of(
                "name", name == null ? "" : name,
                "text", text == null ? "" : text));
    }
}
