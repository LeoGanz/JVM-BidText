package edu.purdue.cs.toydroid.bidtext.analysis;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class SensitiveTerms implements Iterable<SensitiveTerms.SensitiveTerm> {

    private static final boolean ACCEPT_ONLY_COMPLETE_WORDS = false;
    public static final String TERM_FILE = "res/SensitiveTerms.txt";
    private static final String FORBIDDEN_PREFIX = "class.{0,25}";
    private static final String FORBIDDEN_SUFFIX = "_?type";
    private final String termFile;
    private final Set<SensitiveTerm> terms;

    public SensitiveTerms(String inputFile) throws IOException {
        this.termFile = inputFile;
        terms = new HashSet<>();
        collectTerms();
    }

    public static void main(String[] args) throws IOException {
        SensitiveTerms sensitiveTerms = new SensitiveTerms(TERM_FILE);
        for (SensitiveTerm term : sensitiveTerms) {
            System.out.println(term.tag() + "\n" + term.pattern() + "\n");
        }
    }

    private void collectTerms() throws IOException {
        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(new FileInputStream(termFile), StandardCharsets.UTF_8));
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
    }

    private void addTerm(String tag, StringBuilder regex) {
        StringBuilder pattern = new StringBuilder();
        if (ACCEPT_ONLY_COMPLETE_WORDS) {
            pattern.append("\\b");
        }
        if (FORBIDDEN_PREFIX != null) {
            pattern.append("(?<!^(").append(FORBIDDEN_PREFIX).append("))");
        }
        pattern.append("(").append(regex).append(")");
        if (FORBIDDEN_SUFFIX != null) {
            pattern.append("(?!(").append(FORBIDDEN_SUFFIX).append("))");
        }
        if (ACCEPT_ONLY_COMPLETE_WORDS) {
            pattern.append("\\b");
        }
        terms.add(new SensitiveTerm(tag, Pattern.compile(pattern.toString())));
    }

    @Override
    public Iterator<SensitiveTerm> iterator() {
        return terms.iterator();
    }

    public record SensitiveTerm(String tag, Pattern pattern) {}
}
