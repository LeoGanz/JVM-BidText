package edu.purdue.cs.toydroid.bidtext.java;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import edu.purdue.cs.toydroid.bidtext.java.spring.IocInjector;
import edu.purdue.cs.toydroid.utils.SimpleConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CustomClassHierarchyFactory {

    private static final Logger logger = LogManager.getLogger(CustomClassHierarchyFactory.class);
    private static final boolean DO_SPRING_PREPROCESSING_DEFAULT = false;

    public static ClassHierarchy make(String pathToJarOrClassesRootFolder, AnalysisCache cache) throws IOException,
            ClassHierarchyException, InvalidClassFileException {
        return make(pathToJarOrClassesRootFolder, cache, DO_SPRING_PREPROCESSING_DEFAULT);
    }

    public static ClassHierarchy make(String pathToJarOrClassesRootFolder, AnalysisCache cache,
                                      boolean doSpringProcessing) throws IOException, ClassHierarchyException,
            InvalidClassFileException {
        String inclusionsFilePath = SimpleConfig.getInclusionsFile();
        String exclusionFilePath = SimpleConfig.getExclusionsFile();
        File exclusionsFile = exclusionFilePath != null ? new File(exclusionFilePath) : null;
        ClassLoader classLoader = CustomClassHierarchyFactory.class.getClassLoader();

        AnalysisScope scope =
                AnalysisScopeReader.instance.readJavaScope(inclusionsFilePath, exclusionsFile, classLoader);
        ClassLoaderReference walaClassLoader = scope.getLoader(AnalysisScope.APPLICATION);
        AnalysisScopeReader.instance.addClassPathToScope(pathToJarOrClassesRootFolder, scope, walaClassLoader);
        ClassHierarchy basicClassHierarchy = ClassHierarchyFactory.make(scope);
        printDebugInfo(basicClassHierarchy);

        if (doSpringProcessing) {
            return IocInjector.buildAdaptedClassHierarchy(pathToJarOrClassesRootFolder, basicClassHierarchy, scope,
                    cache);
        } else {
            return basicClassHierarchy;
        }
    }

    private static void printDebugInfo(ClassHierarchy basicClassHierarchy) {
        Set<IClass> appClasses = StreamSupport.stream(basicClassHierarchy.spliterator(), false)
                .filter(clazz -> clazz.getClassLoader().getReference().equals(ClassLoaderReference.Application))
                .collect(Collectors.toSet());
        appClasses.forEach(clazz -> {
            logger.debug("App class: " + clazz.getName());
            if (clazz.getName().toString().contains("SpringIOCModel")) {
                clazz.getDeclaredMethods().forEach(method -> logger.debug("    Method: " + method.getSignature()));
            }
        });
    }
}
