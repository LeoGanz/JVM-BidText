package de.lmu.ifi.jvmbidtext.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

public class SimpleConfig {
    private static final Logger logger = LogManager.getLogger(SimpleConfig.class);

    private static final String PROPERTIES = "res/Config.properties";

    private static boolean configParsed = false;
    private static String inclusionsFile;
    private static String exclusionFile;
    private static String sinkDefinitionsFile;
    private static String artificialSourcesFile;
    private static String apiPropagationRulesFile;
    private static String sensitiveTermsFile;

    private static boolean matchOnlyWholeWordsInTextAnalysis;
    private static boolean enableSpringDiPreprocessing;
    private static boolean enableGarbageCollectorHintAfterIntermediateClassHierarchy;
    private static boolean enableSpringEntrypointDiscovery;
    private static Set<String> prefixesOfCallbackMethods;
    private static boolean useAnyMethodWithPrefixAsEntrypoint;
    private static boolean useWorkaroundForAbstract;
    private static int timeout;
    private static int thresholdContextInsensitive;
    private static int thresholdSkipEntrypoint;

    private static void parseConfig() throws IOException {
        if (configParsed) {
            return;
        }
        InputStream is = new FileInputStream(PROPERTIES);
        Properties prop = new Properties();
        prop.load(is);
        inclusionsFile = prop.getProperty("INCLUSIONS");
        exclusionFile = prop.getProperty("EXCLUSIONS");
        sinkDefinitionsFile = prop.getProperty("SINK_DEFINITIONS");
        artificialSourcesFile = prop.getProperty("ARTIFICIAL_SOURCES");
        apiPropagationRulesFile = prop.getProperty("API_PROPAGATION_RULES");
        sensitiveTermsFile = prop.getProperty("SENSITIVE_TERMS");

        matchOnlyWholeWordsInTextAnalysis = Boolean.parseBoolean(prop.getProperty("MATCH_ONLY_WHOLE_WORDS_IN_TEXT_ANALYSIS"));
        enableSpringDiPreprocessing = Boolean.parseBoolean(prop.getProperty("ENABLE_SPRING_DI_PREPROCESSING"));
        enableGarbageCollectorHintAfterIntermediateClassHierarchy = Boolean.parseBoolean(prop.getProperty("ENABLE_GARBAGE_COLLECTOR_HINT_AFTER_INTERMEDIATE_CLASS_HIERARCHY"));
        enableSpringEntrypointDiscovery = Boolean.parseBoolean(prop.getProperty("ENABLE_SPRING_ENTRYPOINT_DISCOVERY"));
        prefixesOfCallbackMethods = Set.of(prop.getProperty("PREFIXES_OF_CALLBACK_METHODS").split(","));
        useAnyMethodWithPrefixAsEntrypoint = Boolean.parseBoolean(prop.getProperty("USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT"));
        useWorkaroundForAbstract = Boolean.parseBoolean(prop.getProperty("USE_WORKAROUND_FOR_ABSTRACT"));
        timeout = Integer.parseInt(prop.getProperty("TIMEOUT"));
        thresholdContextInsensitive = Integer.parseInt(prop.getProperty("THRESHOLD_CONTEXT_INSENSITIVE"));
        thresholdSkipEntrypoint = Integer.parseInt(prop.getProperty("THRESHOLD_SKIP_ENTRYPOINT"));
        is.close();
        configParsed = true;
    }

    public static String getInclusionsFile() throws IOException {
        parseConfig();
        return inclusionsFile;
    }

    public static String getExclusionsFile() throws IOException {
        parseConfig();
        return exclusionFile;
    }

    public static String getSinkDefinitionsFile() throws IOException {
        parseConfig();
        return sinkDefinitionsFile;
    }

    public static String getArtificialSourcesFile() throws IOException {
        parseConfig();
        return artificialSourcesFile;
    }

    public static String getApiPropagationRulesFile() throws IOException {
        parseConfig();
        return apiPropagationRulesFile;
    }

    public static String getSensitiveTermsFile() throws IOException {
        parseConfig();
        return sensitiveTermsFile;
    }

    public static boolean isMatchOnlyWholeWordsInTextAnalysis() throws IOException {
        parseConfig();
        return matchOnlyWholeWordsInTextAnalysis;
    }

    public static boolean isSpringDiPreprocessingEnabled() throws IOException {
        parseConfig();
        return enableSpringDiPreprocessing;
    }

    public static boolean isGarbageCollectorHintAfterIntermediateClassHierarchyEnabled() throws IOException {
        parseConfig();
        return enableGarbageCollectorHintAfterIntermediateClassHierarchy;
    }

    public static boolean isSpringEntrypointDiscoveryEnabled() throws IOException {
        parseConfig();
        return enableSpringEntrypointDiscovery;
    }

    public static Set<String> getPrefixesOfCallbackMethods() throws IOException {
        parseConfig();
        return prefixesOfCallbackMethods;
    }

    public static boolean isUseAnyMethodWithPrefixAsEntrypoint() throws IOException {
        parseConfig();
        return useAnyMethodWithPrefixAsEntrypoint;
    }

    public static boolean isUseWorkaroundForAbstract() throws IOException {
        parseConfig();
        return useWorkaroundForAbstract;
    }

    public static int getTimeout() throws IOException {
        parseConfig();
        return timeout;
    }

    public static int getThresholdContextInsensitive() throws IOException {
        parseConfig();
        return thresholdContextInsensitive;
    }

    public static int getThresholdSkipEntrypoint() throws IOException {
        parseConfig();
        return thresholdSkipEntrypoint;
    }
}
