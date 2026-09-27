// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
/** One hit: the document it matched and the score it matched with. */
public class ScoreDoc {
    public final int doc;
    public final float score;
    public ScoreDoc(int doc, float score) { this.doc = doc; this.score = score; }
}
