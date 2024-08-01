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
    private static final String PREFIX_OF_CALLBACK_METHODS = "on";
    private final Set<Entrypoint> entrypoints = new HashSet<>();
    private final Set<String> entrypointSignatures = new HashSet<>();
    private final IClassHierarchy classHierarchy;

    public static Set<Entrypoint> discover(IClassHierarchy classHierarchy) {
        return new EntrypointDiscovery(classHierarchy).findAllEntrypoints();
    }

    private EntrypointDiscovery(IClassHierarchy classHierarchy) {
        this.classHierarchy = classHierarchy;
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
            if (klass.getClassLoader()
                    .getReference()
                    .equals(ClassLoaderReference.Application) && !klass.isInterface()) {
                callbackMethodEntrypoints(klass);
            }
        }
        logger.info("Callback entrypoints: {}", entrypoints.size() - initialEntrypointCount);
    }

    private void callbackMethodEntrypoints(IClass clazz) {
        Collection<? extends IMethod> methods = clazz.getDeclaredMethods();
        for (IMethod method : methods) {
            String sig = method.getSignature();
            if (method.isPrivate() || method.isAbstract() || entrypointSignatures.contains(sig)) {
                continue;
            }
            String mName = method.getName().toString();
            if (mName.startsWith(PREFIX_OF_CALLBACK_METHODS) && overridingFramework(method)) {
                entrypointSignatures.add(sig);
                addEntrypoint(new DefaultEntrypoint(method, classHierarchy));
            }
        }
    }

    private static boolean overridingFramework(IMethod method) {
//        return method.getName().toString().contains("onConnection");
        return true;
        //TODO check if any superclass that is loaded by Extension ClassLoader has the same method
    }

    private void addEntrypoint(Entrypoint ep) {
        entrypoints.add(ep);
        logger.debug("Discovered entrypoint: {}::{}", ep.getMethod()
                .getDeclaringClass()
                .getName()
                .toString(), ep.getMethod().getName().toString());
    }
}
