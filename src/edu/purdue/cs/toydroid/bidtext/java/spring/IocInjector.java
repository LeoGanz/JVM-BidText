package edu.purdue.cs.toydroid.bidtext.java.spring;

import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container.IocContainerClass;

import java.util.Set;

public class IocInjector {

    public static void doInjection(ClassHierarchy classHierarchy, AnalysisScope scope, AnalysisCache cache) {
        AnnotationFinder annotationFinder = new AnnotationFinder(classHierarchy);
        annotationFinder.processClasses();

        IocContainerClass springIOCModel =
                IocContainerClass.make(annotationFinder, classHierarchy, new AnalysisOptions(scope, Set.of()), cache);
        classHierarchy.addClass(springIOCModel);
    }
}
