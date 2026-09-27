// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.index;
import org.apache.lucene.document.Document;
import java.io.IOException;
/** Random access to the stored fields of one document. */
public abstract class StoredFields {
    public abstract Document document(int docID) throws IOException;
}
