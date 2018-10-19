package datawave.ingest.data.tokenize;

import java.io.IOException;
import java.io.Reader;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.standard.StandardFilter;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.util.Version;

/**
 * Filters {@link StandardTokenizer} with {@link StandardFilter}, {@link LowerCaseFilter} and {@link StopFilter}, using a list of English stop words (unless
 * otherwise specified).
 * <p>
 * This analyzer does NOT provide the ability to apply BASIS RLP processing
 * 
 */
public class StandardAnalyzer extends StopwordAnalyzerBase {
    
    /** Default maximum allowed token length */
    public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;
    public static final int DEFAULT_TRUNCATE_TOKEN_LENGTH = 1024;
    
    private int maxTokenLength = DEFAULT_MAX_TOKEN_LENGTH;
    private int tokenTruncateLength = DEFAULT_MAX_TOKEN_LENGTH;
    
    protected boolean applyAccentFilter = false;
    protected TokenSearch searchUtil;
    
    protected Reader tokenizerReader;
    
    /**
     * An unmodifiable set containing some common English words that are usually not useful for searching.
     */
    public static final CharArraySet STOP_WORDS_SET = StopAnalyzer.ENGLISH_STOP_WORDS_SET;
    
    /**
     * Build an analyzer with the default stop words: ({@link #STOP_WORDS_SET}).
     * <p>
     * This hides the matchVersion parameter we don't always want consumers to have to be concerned with it. Generally matchVersion will be set to the current
     * Lucene version.
     */
    public StandardAnalyzer() {
        this(STOP_WORDS_SET);
    }
    
    /**
     * Build an analyzer with the given specified words.
     * <p>
     * This hides the matchVersion parameter we don't always want consumers to have to be concerned with it. Generally matchVersion will be set to the current
     * Lucene version.
     * 
     * @param stopWords
     */
    public StandardAnalyzer(CharArraySet stopWords) {
        super(stopWords);
        // setVersion(Version.XXXXXX); No Version.LUCENE_47 in 7.5.x. Should we care?
    }
    
    public StandardAnalyzer(TokenSearch searchUtil) {
        super(searchUtil.getInstanceStopwords());
        // setVersion(Version.XXXXXX); No Version.LUCENE_47 in 7.5.x. Should we care?
    }
    
    /**
     * Set maximum allowed token length. If a token is seen that exceeds this length then it is discarded. This setting only takes effect the next time
     * tokenStream or tokenStream is called.
     */
    public void setMaxTokenLength(int length) {
        maxTokenLength = length;
    }
    
    /**
     * Set the length beyond which tokens will be truncated. Tokens longer than this length will be truncated to this length.
     *
     * @param length
     */
    public void setTokenTruncateLength(int length) {
        tokenTruncateLength = length;
    }
    
    /**
     * @see #setMaxTokenLength
     */
    public int getMaxTokenLength() {
        return maxTokenLength;
    }
    
    /**
     * Would've preferred to grab the reader here in tokenStream, but method is 'final' in Analyzer. Of course, could also be that there's a much better way to
     * accomplish the end goal here with more refactoring (?)
     * 
     * @Override public TokenStream tokenStream(final String fieldName, final Reader reader) { tokenizerReader = reader; return super.tokenStream(fieldName,
     *           reader); }
     */
    
    public void setTokenizerReader(final Reader reader) {
        tokenizerReader = reader;
    }
    
    protected StandardTokenizer createTokenizer() {
        
        if (null == tokenizerReader) {
            throw new IllegalStateException("Tokenizer reader cannot be null");
        }
        
        StandardTokenizer src = new StandardTokenizer(tokenizerReader);
        
        if (maxTokenLength > 0) {
            src.setMaxTokenLength(maxTokenLength);
        }
        
        if (tokenTruncateLength > 0) {
            src.setTokenTruncateLength(tokenTruncateLength);
        }
        
        return src;
    }
    
    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        
        StandardTokenizer src = createTokenizer();
        
        // Retain Standard Filter's old APOSTROPHE and ACRONYM handling
        // from Lucene 3.0 <-- Comment no longer valid (How important is it to retain 3.0 behavior?)
        @SuppressWarnings("deprecation")
        TokenStream tok = new StandardFilter(src);
        
        tok = new LowerCaseFilter(tok);
        
        if (stopwords != null) {
            tok = new StopFilter(tok, stopwords);
        }
        
        if (searchUtil != null) {
            tok = new TokenSearchSynonymFilter(tok, searchUtil);
        }
        
        if (applyAccentFilter) {
            tok = new AccentFilter(tok);
        }
        
        return new TokenStreamComponents(src, tok) {
            @Override
            protected void setReader(final Reader reader) {
                src.setMaxTokenLength(StandardAnalyzer.this.maxTokenLength);
                super.setReader(reader);
            }
        };
    }
}
