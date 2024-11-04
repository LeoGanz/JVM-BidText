package de.lmu.ifi.jvmbidtext.analysis;

import de.lmu.ifi.jvmbidtext.utils.SimpleConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class SensitiveTerms implements Iterable<SensitiveTerms.SensitiveTerm> {

    private static final String FORBIDDEN_PREFIX = "class.{0,25}";
    private static final String FORBIDDEN_SUFFIX = "_?type";
    private final Set<SensitiveTerm> terms;

    public SensitiveTerms() {
        terms = new HashSet<>();
        collectTerms();
    }

    public static void main(String[] args) {
        SensitiveTerms sensitiveTerms = new SensitiveTerms();
        for (SensitiveTerm term : sensitiveTerms) {
            System.out.println(term.tag() + "\n" + term.pattern() + "\n");
        }
    }

    private void collectTerms() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                SinkDefinitions.class.getClassLoader().getResourceAsStream(SimpleConfig.getSensitiveTermsFile()))))) {
            String line;
            String tag = null;
            StringBuilder regex = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.startsWith("#") | (line.isEmpty() && (tag == null || regex == null))) {
                    continue;
                }
                if (!line.isEmpty()) {
                    if (tag == null) {
                        tag = line;
                    } else if (regex == null) {
                        regex = new StringBuilder(line);
                    } else {
                        regex.append("|(");
                        regex.append(line);
                        regex.append(")");
                    }
                } else {
                    addTerm(tag, regex);
                    tag = null;
                    regex = null;
                }
            }

            // if last pattern is directly followed by EOF instead of blank line complete pattern if
            // possible
            if (tag != null && regex != null) {
                addTerm(tag, regex);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to sensitive terms file", e);
        }
    }

    private void addTerm(String tag, StringBuilder regex) throws IOException {
        StringBuilder pattern = new StringBuilder();
        boolean matchOnlyWholeWordsInTextAnalysis = SimpleConfig.isMatchOnlyWholeWordsInTextAnalysis();
        if (matchOnlyWholeWordsInTextAnalysis) {
            pattern.append("\\b");
        }
        if (FORBIDDEN_PREFIX != null) {
            pattern.append("(?<!^(").append(FORBIDDEN_PREFIX).append("))");
        }
        pattern.append("(").append(regex).append(")");
        if (FORBIDDEN_SUFFIX != null) {
            pattern.append("(?!(").append(FORBIDDEN_SUFFIX).append("))");
        }
        if (matchOnlyWholeWordsInTextAnalysis) {
            pattern.append("\\b");
        }
        terms.add(new SensitiveTerm(tag, Pattern.compile(pattern.toString())));
    }

    @Override
    public Iterator<SensitiveTerm> iterator() {
        return terms.iterator();
    }

    public record SensitiveTerm(String tag, Pattern pattern) {
    }
}
