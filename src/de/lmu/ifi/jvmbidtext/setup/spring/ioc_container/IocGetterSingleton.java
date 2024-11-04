package de.lmu.ifi.jvmbidtext.setup.spring.ioc_container;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;


public class IocGetterSingleton extends IocGetter {

    private IocGetterSingleton(MethodReference method, IocContainerClass declaringClass, IClassHierarchy cha,
                               AnalysisOptions options, IAnalysisCacheView cache) {
        super(method, declaringClass, cha, options, cache);
    }

    public static IocGetterSingleton make(FieldReference staticFieldReference, IocContainerClass iocClass,
                                          IClassHierarchy cha, AnalysisOptions options, IAnalysisCacheView cache) {
        MethodReference methodReference =
                buildMethodReference(staticFieldReference.getFieldType(), iocClass);
        IocGetterSingleton method = new IocGetterSingleton(methodReference, iocClass, cha, options, cache);
        int valueNumber = method.addGetStatic(staticFieldReference);
        method.addReturn(valueNumber, staticFieldReference.getFieldType().isPrimitiveType());
        return method;
    }

}
