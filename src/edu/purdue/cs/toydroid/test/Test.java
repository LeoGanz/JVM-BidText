package edu.purdue.cs.toydroid.test;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.types.ClassLoaderReference;

import java.util.Iterator;

public class Test {

	public static void main(String[] args) throws Exception {
		Iterable<Entrypoint> entrypoints;
		AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(
				"bin/", null);
		ClassHierarchy cha = ClassHierarchyFactory.make(scope);
		Iterator<IClass> iter = cha.iterator();
		int i = 0;
		while (iter.hasNext()) {
			IClass ik = iter.next();
			if (ik.getClassLoader()
					.getReference()
					.equals(ClassLoaderReference.Application)) {
				System.out.format("[%3d] %s\n", i++, ik.getName().toString());
				System.out.println(ik.getDeclaredMethods());
			}
		}
		entrypoints = Util.makeMainEntrypoints(scope, cha, "Ledu/purdue/cs/toydroid/test/TestCase");
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		CallGraphBuilder builder = Util.makeRTABuilder(options, new AnalysisCacheImpl(), cha, scope);
		CallGraph cg = builder.makeCallGraph(options, null);
		System.out.println(CallGraphStats.getStats(cg));
		Iterator<CGNode> cgIter = cg.iterator();
		while (cgIter.hasNext()) {
			CGNode cn = cgIter.next();
			System.out.println(cn.getMethod());
		}
	}

}
