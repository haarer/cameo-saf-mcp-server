// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.index;
import java.io.IOException;
/** A point-in-time view of an {@link org.apache.lucene.store.Directory}. */
public abstract class DirectoryReader extends IndexReader {
    public static DirectoryReader open(org.apache.lucene.store.Directory directory) throws IOException { return null; }
    public static DirectoryReader open(IndexWriter writer) throws IOException { return null; }
    /** A new reader if the index changed since {@code oldReader}, else null. */
    public static DirectoryReader openIfChanged(IndexReader oldReader) throws IOException { return null; }
}
