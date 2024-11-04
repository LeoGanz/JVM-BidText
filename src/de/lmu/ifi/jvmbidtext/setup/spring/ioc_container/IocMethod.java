package de.lmu.ifi.jvmbidtext.setup.spring.ioc_container;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.MethodReference;

/**
 * IOCMethods are SyntheticMethods. The fact that they extend AbstractRootMethod is a bit of a hack but saves us from
 * having to copy a lot of statement creation utility of code.
 */
public class IocMethod extends AbstractRootMethod {

    public IocMethod(MethodReference method, IocContainerClass declaringClass, IClassHierarchy cha,
                     AnalysisOptions options,
                     IAnalysisCacheView cache) {
        super(method, declaringClass, cha, options, cache);
    }
}
