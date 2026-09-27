// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
import org.apache.lucene.index.Term;
public class TermQuery extends Query {
    public final Term term;
    public TermQuery(Term term) { this.term = term; }
}
