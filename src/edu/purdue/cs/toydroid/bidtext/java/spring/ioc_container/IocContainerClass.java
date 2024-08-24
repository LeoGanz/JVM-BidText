package edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.SyntheticClass;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.UnimplementedError;
import edu.purdue.cs.toydroid.bidtext.java.spring.AnnotationFinder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class IocContainerClass extends SyntheticClass {

    public static final String CLASS_NAME = "Lbidtext/extension/SpringIOCModel";
    public static final TypeReference TYPE_REFERENCE =
            TypeReference.findOrCreate(ClassLoaderReference.Application, TypeName.string2TypeName(CLASS_NAME));

    private final IClassHierarchy classHierarchy;
    private final AnalysisOptions options;
    private final IAnalysisCacheView cache;

    private final Map<Atom, IField> singletonInstances = new HashMap<>();
    private final Map<Selector, IMethod> methods = new HashMap<>();
    private IocClinitMethod classInitializer;

    private IocContainerClass(IClassHierarchy classHierarchy, AnalysisOptions options, IAnalysisCacheView cache) {
        super(TYPE_REFERENCE, classHierarchy);
        this.classHierarchy = classHierarchy;
        this.options = options;
        this.cache = cache;

    }

    public static IocContainerClass make(AnnotationFinder annotationFinder, IClassHierarchy classHierarchy,
                                         AnalysisOptions options, IAnalysisCacheView cache) {
        IocContainerClass iocClass = new IocContainerClass(classHierarchy, options, cache);
        iocClass.prepareClassInitializer();
        annotationFinder.getSingletonBeans()
                .forEach(clazz -> iocClass.registerSingletonBean(clazz.getName().getClassName(), clazz.getReference()));
        annotationFinder.getPrototypeBeans()
                .forEach(clazz -> iocClass.registerPrototypeBean(clazz.getName().getClassName(), clazz.getReference()));
        return iocClass;
    }

    private void registerSingletonBean(Atom name, TypeReference type) {
        IocSingletonField field = IocSingletonField.make(name, type, this);
        addField(field);
        classInitializer.addInitializationForField(field.getReference());
        addMethod(IocGetterSingleton.make(field.getReference(), this, classHierarchy, options, cache));
    }

    private void registerPrototypeBean(Atom name, TypeReference type) {
        addMethod(IocGetterPrototype.make(name, type, this, classHierarchy, options, cache));
    }

    private void prepareClassInitializer() {
        IocClinitMethod clinit = IocClinitMethod.make(this, classHierarchy, options, cache);
        addMethod(clinit);
        classInitializer = clinit;
    }

    private void addField(IocSingletonField field) {
        singletonInstances.put(field.getName(), field);
    }

    private void addMethod(IMethod m) {
        methods.put(m.getSelector(), m);
    }

    @Override
    public IMethod getMethod(Selector selector) throws UnsupportedOperationException {
        return methods.get(selector);
    }


    @Override
    public IMethod getClassInitializer() throws UnimplementedError {
        return classInitializer;
    }

    @Override
    public IField getField(Atom name) {
        return singletonInstances.get(name);
    }


    @Override
    public int getModifiers() throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public IClass getSuperclass() throws UnsupportedOperationException {
        return getClassHierarchy().getRootClass();
    }

    @Override
    public Collection<IClass> getAllImplementedInterfaces() throws UnsupportedOperationException {
        return Collections.emptySet();
    }


    @Override
    public Collection<IMethod> getDeclaredMethods() throws UnsupportedOperationException {
        return methods.values();
    }

    @Override
    public Collection<IField> getDeclaredInstanceFields() throws UnsupportedOperationException {
        return Collections.emptySet();
    }

    @Override
    public Collection<IField> getDeclaredStaticFields() {
        return singletonInstances.values();
    }

    @Override
    public boolean isReferenceType() {
        return getReference().isReferenceType();
    }

    @Override
    public Collection<IClass> getDirectInterfaces() throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<IField> getAllInstanceFields() {
        return Collections.emptySet();
    }

    @Override
    public Collection<IField> getAllStaticFields() {
        return getDeclaredStaticFields();
    }

    @Override
    public Collection<IMethod> getAllMethods() {
        return getDeclaredMethods();
    }

    @Override
    public Collection<IField> getAllFields() {
        return getDeclaredStaticFields();
    }

    @Override
    public boolean isPublic() {
        return true;
    }

    @Override
    public boolean isPrivate() {
        return false;
    }

}
