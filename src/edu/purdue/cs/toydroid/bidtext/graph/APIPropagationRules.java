package edu.purdue.cs.toydroid.bidtext.graph;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class APIPropagationRules {

    private final static String RULE_FILE = "dat/APIRules.txt";
    private final static Pattern RULE_PATTERN = Pattern.compile("^(-?\\d+)([<>]*=[<>]*)(-?\\d+)$");
    private static final Map<String, Set<Rule>> sig2Rules = new HashMap<>();
    private static boolean rulesCollected = false;

    public static Set<Rule> getRules(String sig) {
        collectRules();
        return sig2Rules.computeIfAbsent(sig, __ -> new HashSet<>());
    }

    private static void collectRules() {
        if (rulesCollected) {
            return;
        }
        rulesCollected = true;
        sig2Rules.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(RULE_FILE))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                if (parts.length < 3) {
                    continue;
                }
                String sig = parts[1];
                Set<Rule> rules = sig2Rules.computeIfAbsent(sig, __ -> new HashSet<>());
                for (int i = 2; i < parts.length; i++) {
                    rules.add(Rule.parse(parts[i]));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read rules file", e);
        }
    }

    public record Rule(ValueIndex left, Operator operator, ValueIndex right) {
        private static Rule parse(String rule) {
            Matcher m = RULE_PATTERN.matcher(rule);
            if (!m.find() || m.groupCount() != 3) {
                throw new IllegalArgumentException("Invalid rule format: " + rule);
            }
            String left = m.group(1);
            String op = m.group(2);
            String right = m.group(3);
            return new Rule(ValueIndex.parse(left), Operator.parse(op), ValueIndex.parse(right));
        }

        @Override
        public String toString() {
            return "" + left + operator + right;
        }

        public record ValueIndex(int index) {
            public ValueIndex {
                if (index < -1) {
                    throw new IllegalArgumentException("Value must be -1 or greater");
                }
            }

            public boolean isReturnValue() {
                return index == -1;
            }

            private static ValueIndex parse(String value) {
                return new ValueIndex(Integer.parseInt(value));
            }

            @Override
            public String toString() {
                return index == -1 ? "ret" : Integer.toString(index);
            }
        }

        public enum Operator {
            LEFT_PROP,
            RIGHT_PROP,
            DUAL_PROP,
            NO_PROP;

            private static final Map<String, Operator> SYMBOLS = Map.of(
                    "<=", LEFT_PROP,
                    "=>", RIGHT_PROP,
                    "<=>", DUAL_PROP,
                    ">=<", NO_PROP
            );

            public static Operator parse(String op) {
                Operator operator = SYMBOLS.get(op);
                if (operator == null) {
                    throw new IllegalArgumentException("Invalid operator: " + op);
                }
                return operator;
            }

            @Override
            public String toString() {
                return SYMBOLS.entrySet().stream()
                        .filter(e -> e.getValue() == this)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow();
            }
        }
    }
}
