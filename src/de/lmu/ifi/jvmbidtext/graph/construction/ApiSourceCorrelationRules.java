package de.lmu.ifi.jvmbidtext.graph.construction;

import de.lmu.ifi.jvmbidtext.analysis.SinkDefinitions;
import de.lmu.ifi.jvmbidtext.utils.SimpleConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ApiSourceCorrelationRules {
    private static boolean ruleCollected = false;
    private static Map<String, String> sig2rules;

    public static String getRule(String sig) {
        collectRules();
        return sig2rules.get(sig);
    }

    private static void collectRules() {
        if (ruleCollected) {
            return;
        }
        ruleCollected = true;
        sig2rules = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                SinkDefinitions.class.getClassLoader().getResourceAsStream(SimpleConfig.getArtificialSourcesFile()))))){
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }
                sig2rules.put(parts[0], parts[1]);
            }
        } catch (Exception ignored) {

        }
    }
}
