// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.document;
/** A named value within a {@link Document}. */
public class Field implements org.apache.lucene.index.IndexableField {
    public enum Store { YES, NO }
    public final String name;
    public final String stringValue;
    public final Store store;
    public Field(String name, String value, Store store) {
        this.name = name; this.stringValue = value; this.store = store;
    }
}
