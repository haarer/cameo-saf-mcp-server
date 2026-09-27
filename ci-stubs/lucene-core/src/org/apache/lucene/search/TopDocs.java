// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
/** The top hits of one search, best score first. */
public class TopDocs {
    public final ScoreDoc[] scoreDocs;
    public TopDocs(ScoreDoc[] scoreDocs) { this.scoreDocs = scoreDocs; }
}
