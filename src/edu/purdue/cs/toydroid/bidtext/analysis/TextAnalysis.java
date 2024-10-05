package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ipa.slicer.Statement;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.trees.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class TextAnalysis {

    private static final Logger logger = LogManager.getLogger(TextAnalysis.class);

    private final static String GRAMMAR = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";

    private static LexicalizedParser lexParser;
    private final SensitiveTerms sensitiveTerms;
    private final Map<String, List<Statement>> text2Path;
    private Set<String> sensitiveStrings;

    public TextAnalysis() throws IOException {
        text2Path = new HashMap<>();
        sensitiveTerms = new SensitiveTerms(SensitiveTerms.TERM_FILE);
    }

    public void analyze(Map<String, List<Statement>> texts) {
        sensitiveStrings = new HashSet<>();

        List<String> f = purify(texts);
        // logger.debug("  {}", f.toString());
        checkForNegations(f, texts);
    }

    private List<String> purify(Map<String, List<Statement>> texts) {
        List<String> f = new LinkedList<>();
        Set<String> toRemove = new HashSet<>();
        Set<Map.Entry<String, List<Statement>>> textSet = texts.entrySet();
        for (Map.Entry<String, List<Statement>> entry : textSet) {
            String str = entry.getKey();
            List<Statement> path = entry.getValue();
            if (str.startsWith("http:") || str.startsWith("https:") || str.startsWith("/")) {
                int idx = str.indexOf('?');
                if (idx > 0) {
                    String s = str.substring(idx + 1);
                    if (containsKeywords(s)) {
                        sensitiveStrings.add(s);
                        text2Path.put(str, path);
                        toRemove.add(str);
                    }
                }
            } else if (str.startsWith("&") && str.endsWith("=")) {
                if (containsKeywords(str)) {
                    sensitiveStrings.add(str);
                    text2Path.put(str, path);
                    toRemove.add(str);
                }
            } else if (str.length() == 1 || (!str.isEmpty() && Character.isDigit(str.charAt(0)))) {
                continue;
            } else if (!str.contains(" ")) {
                String splited = insertWhitespaceIntoCamelCase(str);
                if (containsKeywords(str) || containsKeywords(splited)) {
                    sensitiveStrings.add(str);
                    text2Path.put(str, path);
                    toRemove.add(str);
                }
            } else if (containsKeywords(str)) {
                f.add(str);
                // toRemove.add(str);//comment for path retrieve
            }
        }
        for (String s : toRemove) {
            texts.remove(s);
        }
        return f;
    }

    /**
     * insert whitespace in between words concatenated in camel case but keeps continuous upper case sequences
     */
    private static String insertWhitespaceIntoCamelCase(String src) {
        StringBuilder builder = new StringBuilder(src);
        int s = builder.length();
        int idx = 0;
        boolean continuousUpper = false;
        boolean nonLetter = false;
        while (s-- > 0) {
            char ch = builder.charAt(idx);
            if (Character.isUpperCase(ch)) {
                if (!continuousUpper) {
                    if (idx > 0 && !Character.isWhitespace(builder.charAt(idx - 1))) {
                        builder.insert(idx, ' ');
                        idx++;
                    }
                    continuousUpper = true;
                }
                nonLetter = false;
            } else {
                if (Character.isLowerCase(ch)) {
                    if (continuousUpper) {
                        if (idx > 0 && !Character.isWhitespace(builder.charAt(idx - 1))) {
                            builder.insert(idx - 1, ' ');
                            idx++;
                        }
                    } else if (nonLetter) {
                        builder.insert(idx, ' ');
                        idx++;
                    }
                    nonLetter = false;
                } else if (!nonLetter) {
                    builder.insert(idx, ' ');
                    idx++;
                    nonLetter = true;
                }
                continuousUpper = false;
            }
            idx++;
        }
        return builder.toString().trim();
    }

    private void checkForNegations(List<String> f, Map<String, List<Statement>> texts) {
        if (f.isEmpty()) {
            return;
        }
        if (lexParser == null) {
            lexParser = LexicalizedParser.loadModel(GRAMMAR);
        }
        LexicalizedParser lp = lexParser;
        TreebankLanguagePack tlp = lp.getOp().langpack();
        GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();
        for (String s : f) {
            String origStr = s;
            // Tokenizer<? extends HasWord> toke = tlp.getTokenizerFactory()
            // .getTokenizer(new StringReader(s));
            // List<? extends HasWord> sentence = toke.tokenize();
            DocumentPreprocessor tokenizer = new DocumentPreprocessor(new StringReader(s));
            for (List<? extends HasWord> sentence : tokenizer) {
                s = rebuildString(sentence);
                if (!containsKeywords(s.toLowerCase())) {
                    continue;
                }
                Tree parse = lp.parse(sentence);
                // System.err.println(s);
                GrammaticalStructure gs = gsf.newGrammaticalStructure(parse);
                List<TypedDependency> tdl = gs.typedDependenciesCCprocessed();
//                 System.out.println(tdl);
                // parse.pennPrint();
                Tree c = parse.firstChild();
                Label l = c.label();
                // System.err.println(c.getClass());
                // if not a sentence
                if (!l.value().equals("S")) {
                    sensitiveStrings.add(s);
                    text2Path.put(origStr, texts.get(origStr));
                } else {
                    int negIdx = 0;
                    for (TypedDependency td : tdl) {
                        if ("neg".equals(td.reln().toString()) ||
                                // second condition added during rework
                                td.dep().backingLabel().value().equalsIgnoreCase("not")) {
                            break;
                        }
                        negIdx++;
                    }

                    if (negIdx > 0 && negIdx < tdl.size()) {
                        TypedDependency td = tdl.get(negIdx - 1);
                        if (td.reln().toString().equals("aux")) {
                            String ns = td.dep().backingLabel().value().toLowerCase();
                            if ("should".equals(ns) || "shall".equals(ns)
                                /* || "could".equals(ns) || "can".equals(ns) */) {
                                logger.info("    * Negation detected: <<{}>>", s);
                            } else if ("do".equals(ns) &&
                                    (negIdx == 1 || !tdl.get(negIdx - 2).reln().toString().equals("nsubj"))) {
                                logger.info("    * Negation detected: <<{}>>", s);
                            } else {
                                sensitiveStrings.add(s);
                                text2Path.put(origStr, texts.get(origStr));
                            }
                        }
                    } else {
                        sensitiveStrings.add(s);
                        text2Path.put(origStr, texts.get(origStr));
                    }
                }
            }
        }
        // System.err.println(tagBuilder.toString());
    }

    private String rebuildString(List<? extends HasWord> sentence) {
        StringBuilder builder = new StringBuilder();
        for (HasWord w : sentence) {
            builder.append(w.word());
            builder.append(' ');
        }
        return builder.toString().trim();
    }

    private boolean containsKeywords(String string) {
        if (string == null || string.isEmpty()) {
            return false;
        }
        String lowercaseString = string.toLowerCase();
        for (SensitiveTerms.SensitiveTerm term : sensitiveTerms) {
            if (term.pattern().matcher(lowercaseString).find()) {
                return true;
            }
        }
        return false;
    }

    public Map<String, List<Statement>> getText2Path() {
        return text2Path;
    }

    public Set<String> getSensitiveStrings() {
        return sensitiveStrings;
    }
}
