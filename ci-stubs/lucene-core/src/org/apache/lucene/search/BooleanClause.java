// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
/** One query inside a {@link BooleanQuery}, and how it must match. */
public class BooleanClause {
    public enum Occur { MUST, SHOULD, MUST_NOT, FILTER }
    public final Query query;
    public final Occur occur;
    public BooleanClause(Query query, Occur occur) { this.query = query; this.occur = occur; }
}
