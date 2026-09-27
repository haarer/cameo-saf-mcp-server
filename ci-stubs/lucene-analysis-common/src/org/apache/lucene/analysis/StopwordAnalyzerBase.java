// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis;
import java.util.Collections;
/** Base for analyzers configured from a stop-word set. */
public abstract class StopwordAnalyzerBase extends Analyzer {
    protected StopwordAnalyzerBase(CharArraySet stopwords) {}
}
