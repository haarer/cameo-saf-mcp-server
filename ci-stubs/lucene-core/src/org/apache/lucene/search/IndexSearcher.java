// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.search;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.document.Document;
import java.io.IOException;
public class IndexSearcher {
    public IndexSearcher(DirectoryReader reader) {}
    public TopDocs search(Query query, int n) throws IOException { return new TopDocs(new ScoreDoc[0]); }
    public StoredFields storedFields() { return null; }
    // doc(int) is the one read method both distributions expose: 2024x ships
    // Lucene 9.2, which has no storedFields(), and 2026x ships 9.12, whose
    // IndexSearcher still keeps doc(int). The stub mirrors the intersection
    // rather than the newest API, or the plugin would compile in CI against a
    // Lucene that no shipped distribution has.
    public Document doc(int docId) throws IOException { return null; }
}
