package edu.purdue.cs.toydroid.bidtext.java.spring;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.AnnotationsReader;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.annotations.Annotation;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class AnnotationFinder {

    private final ClassHierarchy classHierarchy;

    private final Set<IClass> singletonBeans = new HashSet<>();
    private final Set<IClass> prototypeBeans = new HashSet<>();
    private final Set<IClass> controllers = new HashSet<>();
    private final Set<IField> autowiredFields = new HashSet<>();


    public AnnotationFinder(ClassHierarchy classHierarchy) {
        this.classHierarchy = classHierarchy;
    }

    public void processClasses() {
        Set<IClass> applicationClasses = StreamSupport.stream(classHierarchy.spliterator(), false)
                .filter(clazz -> clazz.getClassLoader().getReference().equals(ClassLoaderReference.Application))
                .collect(Collectors.toSet());
        for (IClass clazz : applicationClasses) {
            AnnotationCategorization categorization = categorize(clazz);
            processCategorization(clazz, categorization);
            autowiredFields.addAll(getAutowiredFields(clazz));
        }
        System.out.println("Singleton Beans: " + singletonBeans);
        System.out.println("Prototype Beans: " + prototypeBeans);
        System.out.println("Controllers: " + controllers);
        System.out.println("Autowired Fields: " + autowiredFields);
    }

    private void processCategorization(IClass clazz, AnnotationCategorization categorization) {
        if (categorization.isBean()) {
            if (categorization.isPrototype()) {
                prototypeBeans.add(clazz);
            } else {
                singletonBeans.add(clazz);
            }
        } else if (categorization.isController()) {
            controllers.add(clazz);
        }
    }

    private AnnotationCategorization categorize(IClass clazz) {
        AnnotationCategorization categorization = new AnnotationCategorization();
        for (Annotation annotation : clazz.getAnnotations()) {
            switch (annotation.getType().getName().toString()) {
                case "Lorg/springframework/web/bind/annotation/RestController":
                case "Lorg/springframework/web/bind/annotation/Controller":
                case "Lorg/springframework/stereotype/Controller":
                    categorization.setController();
                    break;
                case "Lorg/springframework/stereotype/Component":
                case "Lorg/springframework/context/annotation/Configuration":
                case "Lorg/springframework/stereotype/Repository":
                case "Lorg/springframework/stereotype/Service":
                    categorization.setBean();
                    break;
                case "Lorg/springframework/context/annotation/Scope":
                    if (annotation.getNamedArguments()
                            .values()
                            .stream()
                            .filter(annotationArgumentValue -> annotationArgumentValue instanceof AnnotationsReader.ConstantElementValue)
                            .map(annotationArgumentValue -> (AnnotationsReader.ConstantElementValue) annotationArgumentValue)
                            .map(elem -> elem.val)
                            .anyMatch("prototype"::equals)) {
                        categorization.setPrototype();
                    }
                    break;
            }
        }
        return categorization;
    }

    private Set<IField> getAutowiredFields(IClass clazz) {
        return clazz.getAllInstanceFields().stream()
                .filter(this::fieldIsAutowired)
                .collect(Collectors.toSet());
    }

    private boolean fieldIsAutowired(IField field) {
        Collection<Annotation> annotations = field.getAnnotations();
        if (annotations == null) {
            return false;
        }
        for (Annotation annotation : annotations) {
            switch (annotation.getType().getName().toString()) {
                case "Lorg/springframework/beans/factory/annotation/Autowired":
                case "Ljakarta/annotation/Resource":
                    return true;
            }
        }
        return false;
    }

    public Set<IClass> getSingletonBeans() {
        return singletonBeans;
    }

    public Set<IClass> getPrototypeBeans() {
        return prototypeBeans;
    }

    public Set<IClass> getControllers() {
        return controllers;
    }

    public Set<IField> getAutowiredFields() {
        return autowiredFields;
    }

}
