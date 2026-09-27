// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis.standard;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
/** Unicode word/number tokenization with lowercasing. */
public class StandardAnalyzer extends StopwordAnalyzerBase {
    public StandardAnalyzer() { super(null); }
    public StandardAnalyzer(CharArraySet stopwords) { super(stopwords); }
}
