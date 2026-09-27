package com.haarer.saf.mcpserver.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory BM25 text index backed by Lucene ({@link ByteBuffersDirectory}
 * + {@link BM25Similarity}), sized for small-to-medium corpora (hundreds to
 * low tens of thousands of documents) that are rebuilt or updated in place.
 * Nothing ever touches the disk.
 *
 * <p>Text is analyzed with {@link StandardAnalyzer} (unicode word/number
 * tokenization + lowercasing) on both the index and query side, so
 * {@code MyModel::Pkg::Blk} is indexed as the tokens {@code mymodel},
 * {@code pkg}, and {@code blk} and matches queries written with those tokens.
 *
 * <p>Query semantics are chosen per search ({@link MatchPolicy}): with
 * {@code AND}, a document must contain every query term; with {@code OR},
 * any term is a hit and the score reflects all matching terms. Terms are
 * always OR-ed across the configured fields (a term in any field suffices).
 * Per-field boosts are supplied at construction (e.g. {@code name -> 3.0f})
 * so identifier-like fields outweigh body text.
 *
 * <p>Thread-safety: mutations are serialized on the instance; searches read
 * a volatile {@link IndexSearcher} snapshot, so concurrent search and
 * mutation are safe.
 *
 * <p>Lucene is a compileOnly dependency, so no Lucene jars are bundled with
 * the plugin.
 */
public class Bm25Retriever implements Retriever, AutoCloseable {

    private static final String ID_FIELD = "id";

    private final Map<String, Float> fieldBoosts;
    private final Analyzer analyzer;
    private final Directory directory;
    private final IndexWriter writer;
    private DirectoryReader reader;
    private volatile IndexSearcher searcher;
    private boolean closed;

    /**
     * @param fieldBoosts per-field query boost keyed by indexed field name
     *                    (fields not listed default to 1.0); must not be empty
     * @throws IOException if the in-memory index cannot be initialized
     */
    public Bm25Retriever(Map<String, Float> fieldBoosts) throws IOException {
        this(new StandardAnalyzer(), fieldBoosts);
    }

    public Bm25Retriever(Analyzer analyzer, Map<String, Float> fieldBoosts) throws IOException {
        Objects.requireNonNull(analyzer);
        if (fieldBoosts == null || fieldBoosts.isEmpty()) {
            throw new IllegalArgumentException("fieldBoosts must not be empty");
        }
        this.analyzer = analyzer;
        this.fieldBoosts = Map.copyOf(fieldBoosts);
        this.directory = new ByteBuffersDirectory();
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setSimilarity(new BM25Similarity());
        this.writer = new IndexWriter(directory, cfg);
        // Empty initial commit so a DirectoryReader can always be opened,
        // even before the first document exists.
        writer.commit();
        this.reader = DirectoryReader.open(writer);
        this.searcher = new IndexSearcher(this.reader);
    }

    /**
     * Insert or update one document (upsert on {@link IndexedDoc#id()}).
     */
    public synchronized void add(IndexedDoc doc) throws IOException {
        checkOpen();
        writer.updateDocument(new Term(ID_FIELD, doc.id()), toDocument(doc));
        writer.commit();
        reopen();
    }

    /**
     * Atomically replace the whole index with the given corpus.
     */
    public synchronized void replace(Collection<IndexedDoc> docs) throws IOException {
        Objects.requireNonNull(docs);
        checkOpen();
        writer.deleteAll();
        for (IndexedDoc doc : docs) {
            writer.addDocument(toDocument(doc));
        }
        writer.commit();
        reopen();
    }

    /**
     * Remove the document with the given id (no-op if absent).
     */
    public synchronized void remove(String id) throws IOException {
        checkOpen();
        writer.deleteDocuments(new Term(ID_FIELD, id));
        writer.commit();
        reopen();
    }

    /** Number of live (non-deleted) documents. */
    public synchronized int size() {
        return reader.numDocs();
    }

    @Override
    public List<Hit> search(String query, int k, MatchPolicy policy) {
        if (query == null || k <= 0) {
            return List.of();
        }
        IndexSearcher s = this.searcher;
        if (s == null) {
            return List.of();
        }
        BooleanQuery q = buildQuery(query, policy);
        if (q == null) {
            return List.of();
        }
        TopDocs top;
        try {
            top = s.search(q, k);
        } catch (IOException e) {
            throw new IllegalStateException("index search failed", e);
        }
        List<Hit> hits = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc sd : top.scoreDocs) {
            Document doc;
            try {
                doc = s.storedFields().document(sd.doc);
            } catch (IOException e) {
                // Snapshot reader: cannot happen in practice.
                throw new IllegalStateException("stored field read failed", e);
            }
            hits.add(new Hit(doc.get(ID_FIELD), sd.score));
        }
        return List.copyOf(hits);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        searcher = null;
        writer.close();
        reader.close();
    }

    // -- internals ---------------------------------------------------------

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("retriever is closed");
        }
    }

    /** Reopen the reader if the writer committed since the last reopen. */
    private void reopen() throws IOException {
        DirectoryReader fresh = DirectoryReader.openIfChanged(reader);
        if (fresh == null) {
            return;
        }
        DirectoryReader old = reader;
        reader = fresh;
        searcher = new IndexSearcher(fresh);
        old.close();
    }

    private Document toDocument(IndexedDoc doc) {
        Objects.requireNonNull(doc, "doc");
        Document d = new Document();
        d.add(new StringField(ID_FIELD, doc.id(), Field.Store.YES));
        for (Map.Entry<String, String> e : doc.fields().entrySet()) {
            d.add(new TextField(e.getKey(), e.getValue() == null ? "" : e.getValue(), Field.Store.NO));
        }
        return d;
    }

    /**
     * Analyze the query into a boosted BooleanQuery: within a term, clauses
     * are OR-ed across the configured fields (with per-field boosts); across
     * terms, they are combined per {@code policy} (AND &rarr; MUST,
     * OR &rarr; SHOULD).
     *
     * @return the query, or {@code null} if the query analyzes to no terms
     */
    private BooleanQuery buildQuery(String query, MatchPolicy policy) {
        List<String> terms = analyze(query);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder and = new BooleanQuery.Builder();
        for (String word : terms) {
            BooleanQuery.Builder or = new BooleanQuery.Builder();
            for (Map.Entry<String, Float> e : fieldBoosts.entrySet()) {
                Query fieldQuery = new TermQuery(new Term(e.getKey(), word));
                or.add(e.getValue() == 1.0f
                        ? fieldQuery : new BoostQuery(fieldQuery, e.getValue()),
                    BooleanClause.Occur.SHOULD);
            }
            and.add(or.build(), policy == MatchPolicy.AND
                ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
        }
        return and.build();
    }

    private List<String> analyze(String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream("query", new StringReader(text))) {
            ts.reset();
            CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
            while (ts.incrementToken()) {
                terms.add(termAttr.toString());
            }
            ts.end();
        } catch (IOException e) {
            // In-memory analysis cannot fail; treat as no terms.
        }
        return new ArrayList<>(terms);
    }
}
