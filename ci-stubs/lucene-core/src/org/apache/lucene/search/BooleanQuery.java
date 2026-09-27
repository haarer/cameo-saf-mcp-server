// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
import java.util.ArrayList;
import java.util.List;
/** Clauses combined per their {@link BooleanClause.Occur}. */
public class BooleanQuery extends Query {
    public static final class Builder {
        private final List<BooleanClause> clauses = new ArrayList<>();
        public void add(Query query, BooleanClause.Occur occur) { clauses.add(new BooleanClause(query, occur)); }
        public BooleanQuery build() { return new BooleanQuery(); }
    }
}
