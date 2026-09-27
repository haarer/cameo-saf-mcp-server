// Compile-only stub. See ci-stubs/README.md - not shipped with the plugin.
package org.apache.lucene.analysis;
import java.io.IOException;
/** Pulls tokens one at a time; attribute-carrying token state. */
public abstract class TokenStream implements java.io.Closeable {
    public abstract boolean incrementToken() throws IOException;
    public void end() throws IOException {}
    public void reset() throws IOException {}
    /** The attribute of the given class, created on first request. */
    public abstract <T> T addAttribute(Class<T> attClass);
    @Override public abstract void close() throws IOException;
}
