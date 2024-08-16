package edu.purdue.cs.toydroid.bidtext.android.util;

import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.dalvik.classLoader.DexFileModule;
import com.ibm.wala.dalvik.util.AndroidAnalysisScope;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.types.ClassLoaderReference;
import edu.purdue.cs.toydroid.utils.SimpleConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.jar.JarFile;

public class AnalysisScopeUtil {

    private static final Logger logger = LogManager.getLogger(AnalysisScopeUtil.class);

    private static final ClassLoader WALA_CLASSLOADER = AnalysisScopeReader.class.getClassLoader();

    private static final String BASIC_FILE = "primordial.txt";

    private static final String EXCLUSIONS = "AndroidRegressionExclusions.txt";

    private static final String PrimordialTag = "Primordial";
    private static final String ApplicationTag = "Application";

    public static AnalysisScope makeAnalysisScope(String apkFile)
            throws IOException, FileNotFoundException {
        AnalysisScope scope = null;

        String android_jar = SimpleConfig.getAndroidJar();
        String exclusion_file = SimpleConfig.getExclusionFile();
        String additional_jar;

        if (exclusion_file == null) {
            exclusion_file = EXCLUSIONS;
        }

        scope = AnalysisScopeReader.instance.readJavaScope(BASIC_FILE, new File(
                exclusion_file), WALA_CLASSLOADER);

        // behavior of addClassPathToScope has changed with newer version of WALA
        // this line is taken from the old version of addClassPathToScope
        scope.addToScope(ClassLoaderReference.Primordial, new JarFile(android_jar));
//		AndroidAnalysisScope.addClassPathToScope(android_jar, scope,
//				ClassLoaderReference.Primordial);

        Iterator<String> iter = SimpleConfig.iteratorAdditionalJars();
        while (iter.hasNext()) {
            additional_jar = iter.next();
            int idx = additional_jar.indexOf(',');
            if (idx > 0) {
                String ref = additional_jar.substring(0, idx);
                String path = additional_jar.substring(idx + 1);
                ClassLoaderReference clRef = null;
                if (ref.startsWith(PrimordialTag)) {
                    clRef = scope.getPrimordialLoader();
                } else if (ref.startsWith(ApplicationTag)) {
                    clRef = scope.getApplicationLoader();
                } else {
                    logger.warn("Unrecognized ADDITIONAL_JARS in Config.properties.");
                }
                if (clRef != null) {
                    AndroidAnalysisScope.addClassPathToScope(path, scope, clRef);
                }
            }
        }

        scope.setLoaderImpl(ClassLoaderReference.Application,
                "com.ibm.wala.dalvik.classLoader.WDexClassLoaderImpl");

        scope.addToScope(ClassLoaderReference.Application, DexFileModule.make(
                new File(apkFile)));

        return scope;
    }
}
