package edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;

public class IocClinitMethod extends IocMethod {
    private IocClinitMethod(MethodReference method, IocContainerClass declaringClass, IClassHierarchy cha,
                            AnalysisOptions options, IAnalysisCacheView cache) {
        super(method, declaringClass, cha, options, cache);
    }

    public static IocClinitMethod make(IocContainerClass iocClass, IClassHierarchy cha, AnalysisOptions options,
                                       IAnalysisCacheView cache) {
        MethodReference methodReference =
                MethodReference.findOrCreate(iocClass.getReference(), MethodReference.clinitSelector);
        return new IocClinitMethod(methodReference, iocClass, cha, options, cache);
    }

    public void addInitializationForField(FieldReference fieldReference) {
        SSANewInstruction ssaNewInstruction = addAllocation(fieldReference.getFieldType());
        int valueNumber = ssaNewInstruction.getDef();
        addSetStatic(fieldReference, valueNumber);
    }
}
