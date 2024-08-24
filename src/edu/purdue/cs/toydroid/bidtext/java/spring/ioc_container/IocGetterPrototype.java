package edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container;

import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;


public class IocGetterPrototype extends IocGetter {

    private IocGetterPrototype(MethodReference method, IocContainerClass declaringClass, IClassHierarchy cha,
                               AnalysisOptions options,
                               IAnalysisCacheView cache) {
        super(method, declaringClass, cha, options, cache);
    }

    public static IocGetterPrototype make(Atom name, TypeReference type, IocContainerClass iocClass,
                                          IClassHierarchy cha,
                                          AnalysisOptions options,
                                          IAnalysisCacheView cache) {
        MethodReference methodReference = buildMethodReference(name, type, iocClass);
        IocGetterPrototype method =
                new IocGetterPrototype(methodReference, iocClass, cha, options, cache);
        SSANewInstruction ssaNewInstruction = method.addAllocation(type);
        int valueNumber = ssaNewInstruction.getDef();
        method.addReturn(valueNumber, type.isPrimitiveType());
        return method;
    }

}
