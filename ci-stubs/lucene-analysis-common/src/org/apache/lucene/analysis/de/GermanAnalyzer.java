// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis.de;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
/** German stop-word analyzer. */
public class GermanAnalyzer extends StopwordAnalyzerBase {
    public GermanAnalyzer() { super(null); }
    public GermanAnalyzer(CharArraySet stopwords) { super(stopwords); }
    public static CharArraySet getDefaultStopSet() { return new CharArraySet(0, false); }
}
