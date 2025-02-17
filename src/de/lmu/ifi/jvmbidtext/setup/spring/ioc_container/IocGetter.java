package de.lmu.ifi.jvmbidtext.setup.spring.ioc_container;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;


public class IocGetter extends IocMethod {

    IocGetter(MethodReference method, IocContainerClass declaringClass, IClassHierarchy cha, AnalysisOptions options,
              IAnalysisCacheView cache) {
        super(method, declaringClass, cha, options, cache);
    }

    static MethodReference buildMethodReference(TypeReference beanType, IocContainerClass iocClass) {
        String selectorStr = getPlainMethodName(beanType) + "()" + beanType.getName().toString();
        Selector selector = Selector.make(selectorStr);
        return MethodReference.findOrCreate(iocClass.getReference(), selector);
    }

    public static String getPlainMethodName(TypeReference beanType) {
        return "get" + beanType.getName().getClassName().toString();
    }

}
