// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.index;
/** A field name paired with an exact value. */
public final class Term {
    public final String field;
    public final String text;
    public Term(String field, String text) { this.field = field; this.text = text; }
    @Override public String toString() { return field + ":" + text; }
}
