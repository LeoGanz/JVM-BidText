package edu.purdue.cs.toydroid.bidtext.java;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import edu.purdue.cs.toydroid.bidtext.java.spring.AnnotationFinder;
import edu.purdue.cs.toydroid.bidtext.java.spring.IocInjector;
import edu.purdue.cs.toydroid.utils.SimpleConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CustomClassHierarchyFactory {

    private static final Logger logger = LogManager.getLogger(CustomClassHierarchyFactory.class);

    private Optional<Set<IMethod>> springControllerHandlerMethods = Optional.empty();

    public ClassHierarchy make(String pathToJarOrClassesRootFolder, AnalysisCache cache) throws IOException, ClassHierarchyException, InvalidClassFileException {
        return make(pathToJarOrClassesRootFolder, cache, SimpleConfig.isSpringPreprocessingEnabled());
    }

    public ClassHierarchy make(String pathToJarOrClassesRootFolder, AnalysisCache cache,boolean doSpringProcessing) throws IOException, ClassHierarchyException,
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
            AnnotationFinder annotationFinder = new AnnotationFinder(basicClassHierarchy);
            annotationFinder.processClasses();
            springControllerHandlerMethods = Optional.of(annotationFinder.getControllerHandlerMethods());
            return IocInjector.buildAdaptedClassHierarchy(this, pathToJarOrClassesRootFolder, annotationFinder, scope,
                    cache);
        } else {
            return basicClassHierarchy;
        }
    }

    private void printDebugInfo(ClassHierarchy classHierarchy) {
        Set<IClass> appClasses = StreamSupport.stream(classHierarchy.spliterator(), false)
                .filter(clazz -> clazz.getClassLoader().getReference().equals(ClassLoaderReference.Application))
                .collect(Collectors.toSet());
        appClasses.forEach(clazz -> {
            logger.debug("App class: " + clazz.getName());
            if (clazz.getName().toString().contains("SpringIOCModel")) {
                clazz.getDeclaredMethods().forEach(method -> logger.debug("    Method: " + method.getSignature()));
            }
        });
    }

    /**
     * Only available after method make() has been called and spring preprocessing is enabled.
     */
    public Optional<Set<IMethod>> getSpringControllerHandlerMethods() {
        return springControllerHandlerMethods;
    }
}
