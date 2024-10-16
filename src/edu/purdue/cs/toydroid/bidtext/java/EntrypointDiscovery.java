package edu.purdue.cs.toydroid.bidtext.java;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class EntrypointDiscovery {

    private static final Logger logger = LogManager.getLogger(EntrypointDiscovery.class);
    private static final boolean CONSIDER_OVERRIDING_PRIMORDIAL_AS_OVERRIDING_FRAMEWORK = true;
    private static final String PREFIX_OF_CALLBACK_METHODS = "on";
    private static final boolean USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT = true;
    private final Set<Entrypoint> entrypoints = new HashSet<>();
    private final Set<String> entrypointSignatures = new HashSet<>();
    private final IClassHierarchy classHierarchy;

    private EntrypointDiscovery(IClassHierarchy classHierarchy) {
        this.classHierarchy = classHierarchy;
    }

    public static Set<Entrypoint> discover(IClassHierarchy classHierarchy) {
        return new EntrypointDiscovery(classHierarchy).findAllEntrypoints();
    }

    private Set<Entrypoint> findAllEntrypoints() {
        mainMethodEntrypoints();
        callbackMethodEntrypoints();
        return entrypoints;
    }

    private void mainMethodEntrypoints() {
        int initialEntrypointCount = entrypoints.size();
        Util.makeMainEntrypoints(classHierarchy).forEach(this::addEntrypoint);
        logger.info("Main entrypoints: {}", entrypoints.size() - initialEntrypointCount);
    }

    private void callbackMethodEntrypoints() {
        int initialEntrypointCount = entrypoints.size();
        for (IClass klass : classHierarchy) {
            if (klass.getClassLoader().getReference().equals(ClassLoaderReference.Application) &&
                    !klass.isInterface()) {
                callbackMethodEntrypoints(klass);
            }
        }
        logger.info("Callback entrypoints: {}", entrypoints.size() - initialEntrypointCount);
    }

    private void callbackMethodEntrypoints(IClass clazz) {
        Collection<? extends IMethod> methods = clazz.getAllMethods();
        for (IMethod method : methods) {
            if (method.isPrivate() || method.isAbstract()) {
                continue;
            }
            String methodName = method.getName().toString();
            if (methodName.startsWith(PREFIX_OF_CALLBACK_METHODS) &&
                    (overridingFramework(method) || overridingAbstract(method) || USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT)) {
                addEntrypoint(new DefaultEntrypoint(method, classHierarchy));
            }
        }
    }

    // WALA CHA callgraph cannot handle abstract methods
    // therefore we need to consider implementations of abstract methods as entrypoints
    private static boolean overridingAbstract(IMethod method) {
        IMethod methodInAnySuperclasses = findSuperclassMethod(method);
        if (methodInAnySuperclasses == null) {
            return false;
        }
        return methodInAnySuperclasses.isAbstract();
    }

    private static boolean overridingFramework(IMethod method) {
        //TODO handle anonymous classes

        IMethod methodInAnySuperclasses = findSuperclassMethod(method);
        if (methodInAnySuperclasses == null) {
            return false;
        }
        ClassLoaderReference classLoaderOfSuperclassMethod =
                methodInAnySuperclasses.getDeclaringClass().getClassLoader().getReference();
        if (ClassLoaderReference.Extension.equals(classLoaderOfSuperclassMethod)) {
            return true;
        }
        if (ClassLoaderReference.Primordial.equals(classLoaderOfSuperclassMethod)) {
            return CONSIDER_OVERRIDING_PRIMORDIAL_AS_OVERRIDING_FRAMEWORK;
        }
        // found a method with same name in any superclass, but it is still in a class loaded by the Application classloader
        return overridingFramework(methodInAnySuperclasses);

        // recursion is guaranteed to terminate because the superclass chain is finite
        // getSuperclass() returns null for Object class which terminates the recursion
    }

    private static IMethod findSuperclassMethod(IMethod method) {
        IClass superclass = method.getDeclaringClass().getSuperclass();
        if (superclass == null) { // superclass is Object
            return null;
        }

        // can be even higher in the hierarchy than 'superclass'
        return superclass.getMethod(method.getSelector());
    }

    private void addEntrypoint(Entrypoint ep) {
        String sig = ep.getMethod().getSignature();
        if (entrypointSignatures.contains(sig)) {
            return;
        }
        entrypointSignatures.add(sig);
        entrypoints.add(ep);
        logger.debug("Discovered entrypoint: {}::{}", ep.getMethod().getDeclaringClass().getName().toString(),
                ep.getMethod().getName().toString());
    }
}
