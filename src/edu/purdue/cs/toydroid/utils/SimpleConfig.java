package edu.purdue.cs.toydroid.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class SimpleConfig {
    private static final Logger logger = LogManager.getLogger(SimpleConfig.class);

    private static final String PROPERTIES = "res/Config.properties";

    private static boolean configParsed = false;
    private static String inclusionsFile;
    private static String exclusionFile;
    private static String sinkDefinitionsFile;
    private static String artificialSourcesFile;
    private static String apiPropagationRulesFile;

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
}
