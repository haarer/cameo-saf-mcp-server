// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
/** Turns text into a {@link TokenStream} for indexing or querying. */
public abstract class Analyzer implements Closeable {
    /** Concrete in the stub so the concrete analyzers need no bodies. */
    public TokenStream tokenStream(String fieldName, Reader reader) throws IOException { return null; }
    @Override public void close() throws IOException {}
}
