package edu.purdue.cs.toydroid.bidtext.java.spring;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.AnnotationsReader;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.annotations.Annotation;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class AnnotationFinder {

    private final ClassHierarchy classHierarchy;

    private final Set<IClass> singletonBeans = new HashSet<>();
    private final Set<IClass> prototypeBeans = new HashSet<>();
    private final Set<IClass> controllers = new HashSet<>();


    public AnnotationFinder(ClassHierarchy classHierarchy) {
        this.classHierarchy = classHierarchy;
    }

    public void findAnnotations() {
        Set<IClass> applicationClasses = StreamSupport.stream(classHierarchy.spliterator(), false)
                .filter(clazz -> clazz.getClassLoader().getReference().equals(ClassLoaderReference.Application))
                .collect(Collectors.toSet());
        for (IClass clazz : applicationClasses) {
            AnnotationCategorization categorization = categorize(clazz);
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
        System.out.println("Singleton Beans: " + singletonBeans);
        System.out.println("Prototype Beans: " + prototypeBeans);
        System.out.println("Controllers: " + controllers);
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

}
