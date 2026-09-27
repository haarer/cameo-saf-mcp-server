// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
/** Wraps a query with a per-query score multiplier. */
public class BoostQuery extends Query {
    public final Query query;
    public final float boost;
    public BoostQuery(Query query, float boost) { this.query = query; this.boost = boost; }
}
