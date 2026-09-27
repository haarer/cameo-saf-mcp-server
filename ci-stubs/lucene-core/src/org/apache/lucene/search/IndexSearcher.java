// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import java.io.IOException;
public class IndexSearcher {
    public IndexSearcher(DirectoryReader reader) {}
    public TopDocs search(Query query, int n) throws IOException { return new TopDocs(new ScoreDoc[0]); }
    public StoredFields storedFields() { return null; }
}
