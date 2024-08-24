package edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.annotations.Annotation;

import java.util.Collection;
import java.util.Collections;

public class IocSingletonField implements IField {

    private final Atom name;
    private final TypeReference fieldType;
    private final IocContainerClass iocClass;

    private IocSingletonField(Atom name, TypeReference fieldType, IocContainerClass iocClass) {
        this.name = name;
        this.fieldType = fieldType;
        this.iocClass = iocClass;
    }

    public static IocSingletonField make(Atom name, TypeReference fieldType, IocContainerClass iocClass) {
        return new IocSingletonField(name, fieldType, iocClass);
    }

    @Override
    public IClassHierarchy getClassHierarchy() {
        return iocClass.getClassHierarchy();
    }

    @Override
    public TypeReference getFieldTypeReference() {
        return fieldType;
    }

    @Override
    public IClass getDeclaringClass() {
        return iocClass;
    }

    @Override
    public Atom getName() {
        return name;
    }

    @Override
    public boolean isStatic() {
        return true;
    }

    @Override
    public boolean isVolatile() {
        return false;
    }

    @Override
    public FieldReference getReference() {
        return FieldReference.findOrCreate(iocClass.getReference(), name, fieldType);
    }

    @Override
    public boolean isFinal() {
        return false;
    }

    @Override
    public boolean isPrivate() {
        return true;
    }

    @Override
    public boolean isProtected() {
        return false;
    }

    @Override
    public boolean isPublic() {
        return false;
    }

    @Override
    public Collection<Annotation> getAnnotations() {
        return Collections.emptySet();
    }
}
