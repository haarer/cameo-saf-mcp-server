// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.document;
import java.util.ArrayList;
import java.util.List;
/** An ordered collection of {@link Field}s, one per indexed document. */
public class Document implements Iterable<org.apache.lucene.index.IndexableField> {
    private final List<Field> fields = new ArrayList<>();
    public void add(Field field) { fields.add(field); }
    /** The stored value of the first field with this name, or null. */
    public String get(String name) {
        for (Field f : fields) { if (f.name.equals(name)) return f.stringValue; }
        return null;
    }
    @Override public java.util.Iterator<org.apache.lucene.index.IndexableField> iterator() {
        return new java.util.ArrayList<org.apache.lucene.index.IndexableField>(fields).iterator();
    }
}
