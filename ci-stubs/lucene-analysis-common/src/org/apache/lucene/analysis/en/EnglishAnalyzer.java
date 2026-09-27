// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis.en;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import java.util.Collections;
/** English stop-word analyzer. */
public class EnglishAnalyzer extends StopwordAnalyzerBase {
    public static final CharArraySet ENGLISH_STOP_WORDS_SET = new CharArraySet(0, false);
    public EnglishAnalyzer() { super(ENGLISH_STOP_WORDS_SET); }
    public EnglishAnalyzer(CharArraySet stopwords) { super(stopwords); }
    public static CharArraySet getDefaultStopSet() { return ENGLISH_STOP_WORDS_SET; }
}
