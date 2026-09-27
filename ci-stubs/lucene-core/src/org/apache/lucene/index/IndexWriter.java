// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.index;
import org.apache.lucene.store.Directory;
import java.io.IOException;
public class IndexWriter implements java.io.Closeable {
    public IndexWriter(Directory directory, IndexWriterConfig config) throws IOException {}
    public long addDocument(Iterable<? extends IndexableField> fields) throws IOException { return 0; }
    public long updateDocument(Term term, Iterable<? extends IndexableField> fields) throws IOException { return 0; }
    public long deleteAll() throws IOException { return 0; }
    public void deleteDocuments(Term... terms) throws IOException {}
    public long commit() throws IOException { return 0; }
    @Override public void close() throws IOException {}
}
