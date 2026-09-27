// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis.tokenattributes;
/** The token's text, as a {@link CharSequence}. */
public interface CharTermAttribute extends CharSequence {
    CharTermAttribute setEmpty();
    CharTermAttribute setLength(int length);
    char[] buffer();
    int length();
    char charAt(int index);
    CharTermAttribute append(CharSequence csq);
    CharTermAttribute append(char c);
}
