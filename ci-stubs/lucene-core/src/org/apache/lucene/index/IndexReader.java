// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.index;
import java.io.IOException;
public abstract class IndexReader implements java.io.Closeable {
    public abstract int numDocs();
    @Override public abstract void close() throws IOException;
}
