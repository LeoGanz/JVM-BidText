package de.lmu.ifi.jvmbidtext.analysis;

import com.ibm.wala.ipa.slicer.Statement;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.trees.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;

public class TextAnalysis {

    private static final Logger logger = LogManager.getLogger(TextAnalysis.class);

    private final static String GRAMMAR = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";

    private static LexicalizedParser lexParser;
    private final SensitiveTerms sensitiveTerms;
    private final Map<String, List<Statement>> text2Path;
    private Set<String> sensitivityIndicators;

    public TextAnalysis() {
        text2Path = new HashMap<>();
        sensitiveTerms = new SensitiveTerms();
    }

    public void analyze(Map<String, List<Statement>> texts) {
        sensitivityIndicators = new HashSet<>();
        analyzeSensitivity(texts);
    }

    private void analyzeSensitivity(Map<String, List<Statement>> texts) {
        Set<Map.Entry<String, List<Statement>>> textSet = texts.entrySet();
        for (Map.Entry<String, List<Statement>> entry : textSet) {
            String text = entry.getKey();
            List<Statement> path = entry.getValue();
            if (text.startsWith("http:") || text.startsWith("https:") || text.startsWith("/")) {
                int idx = text.indexOf('?');
                if (idx > 0) {
                    String s = text.substring(idx + 1);
                    recordIfContainsKeyword(s, text, path);
                }
            } else if (text.startsWith("&") && text.endsWith("=")) {
                recordIfContainsKeyword(text, text, path);
            } else if (text.length() == 1 || (!text.isEmpty() && Character.isDigit(text.charAt(0)))) {
                continue;
            } else if (!text.contains(" ")) {
                recordIfContainsKeyword(text, text, path);
            } else if (findKeyword(text).isPresent()) {
                recordIfNotNegated(text, path);
            }
        }
    }

    private void recordIfContainsKeyword(String textPartAnalyzed, String completeText, List<Statement> path) {
        findKeyword(textPartAnalyzed).ifPresent(match -> {
            sensitivityIndicators.add(match);
            text2Path.put(completeText, path);
        });
    }

    private void recordIfNotNegated(String origStr, List<Statement> path) {
        if (lexParser == null) {
            lexParser = LexicalizedParser.loadModel(GRAMMAR);
        }
        LexicalizedParser lp = lexParser;
        TreebankLanguagePack tlp = lp.getOp().langpack();
        GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();
        // Tokenizer<? extends HasWord> toke = tlp.getTokenizerFactory()
        // .getTokenizer(new StringReader(origStr));
        // List<? extends HasWord> sentence = toke.tokenize();
        DocumentPreprocessor tokenizer = new DocumentPreprocessor(new StringReader(origStr));
        for (List<? extends HasWord> sentence : tokenizer) {
            String s = rebuildString(sentence);
            if (findKeyword(s).isEmpty()) {
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
                // Not a sentence
                recordIfContainsKeyword(s, origStr, path);
                continue;
            }

            int negIdx = 0;
            for (TypedDependency td : tdl) {
                if ("neg".equals(td.reln().toString()) ||
                        // second condition added during rework
                        td.dep().backingLabel().value().equalsIgnoreCase("not")) {
                    break;
                }
                negIdx++;
            }

            if (negIdx <= 0 || negIdx >= tdl.size()) {
                // no negation found
                recordIfContainsKeyword(s, origStr, path);
                continue;
            }

            TypedDependency td = tdl.get(negIdx - 1);
            if (td.reln().toString().equals("aux")) {
                String ns = td.dep().backingLabel().value().toLowerCase();
                if ("should".equals(ns) || "shall".equals(ns)
                    /* || "could".equals(ns) || "can".equals(ns) */) {
                    logger.info("    * Negation detected: <<{}>>", s);
                } else if ("do".equals(ns) && (negIdx == 1 || !tdl.get(negIdx - 2).reln().toString().equals("nsubj"))) {
                    logger.info("    * Negation detected: <<{}>>", s);
                } else {
                    recordIfContainsKeyword(s, origStr, path);
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

    private Optional<String> findKeyword(String string) {
        if (string == null || string.isEmpty()) {
            return Optional.empty();
        }
        String lowercaseString = string.toLowerCase();
        String withCamelToWhitespace = insertWhitespaceIntoCamelCase(string);
        String withoutUnderscore = withCamelToWhitespace.replace('_', ' ');
        for (SensitiveTerms.SensitiveTerm term : sensitiveTerms) {
            // patterns could require underscores
            Matcher matcher = term.pattern().matcher(lowercaseString);
            if (matcher.find()) {
                String matched = matcher.group();
                return Optional.of(matched);
            }

            // search should also match if parts of words are concatenated in camel case or with underscores
            matcher = term.pattern().matcher(withoutUnderscore);
            if (matcher.find()) {
                String matched = matcher.group();
                return Optional.of(matched);
            }
        }
        return Optional.empty();
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

    public Map<String, List<Statement>> getText2Path() {
        return text2Path;
    }

    public Set<String> getSensitivityIndicators() {
        return sensitivityIndicators;
    }
}
