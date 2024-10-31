package edu.purdue.cs.toydroid.bidtext.java.spring;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.shrike.shrikeBT.*;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.ClassInstrumenter;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.OfflineInstrumenter;
import com.ibm.wala.shrike.shrikeCT.ClassReader;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.MethodReference;
import edu.purdue.cs.toydroid.bidtext.java.CustomClassHierarchyFactory;
import edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container.IocContainerClass;
import edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container.IocGetter;
import edu.purdue.cs.toydroid.utils.SimpleConfig;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class IocInjector {

    private static final String INSTRUMENTED_JAR_FILE_NAME = "bidtext-simulated-ioc.jar";

    private static String getOutputJarPath() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        return tmpDir + File.separator + INSTRUMENTED_JAR_FILE_NAME;
    }

    public static ClassHierarchy buildAdaptedClassHierarchy(CustomClassHierarchyFactory customClassHierarchyFactory,
                                                            String pathToJarOrClassesRootFolder,
                                                            AnnotationFinder annotationFinder, AnalysisScope scope,
                                                            AnalysisCache cache) throws InvalidClassFileException,
            IOException, ClassHierarchyException {


        initializeAutowiredFields(pathToJarOrClassesRootFolder, annotationFinder);

        // To model der Spring functionality, we load the class hierarchy twice.
        // The intermediate class hierarchy is no longer needed at this point
        if (SimpleConfig.isEnableGarbageCollectorHintAfterIntermediateClassHierarchy()) {
            System.gc();
        }

        return buildAdaptedClassHierarchyFromInstrumentedJarFile(customClassHierarchyFactory, scope, cache);
    }

    private static ClassHierarchy buildAdaptedClassHierarchyFromInstrumentedJarFile(
            CustomClassHierarchyFactory customClassHierarchyFactory, AnalysisScope scope, AnalysisCache cache) throws
            IOException, ClassHierarchyException, InvalidClassFileException {
        ClassHierarchy adjustedClassHierarchy = customClassHierarchyFactory.make(getOutputJarPath(), cache, false);
        AnnotationFinder annotationFinder = new AnnotationFinder(adjustedClassHierarchy);
        annotationFinder.processClasses();
        AnalysisOptions options = new AnalysisOptions(scope, Set.of());
        options.setReflectionOptions(AnalysisOptions.ReflectionOptions.FULL);
        IocContainerClass springIOCModel =
                IocContainerClass.make(annotationFinder, adjustedClassHierarchy, options, cache);
        boolean addSuccessful = adjustedClassHierarchy.addClass(springIOCModel);
        if (!addSuccessful) {
            throw new RuntimeException("Failed to add Spring IOC model to class hierarchy");
        }
        return adjustedClassHierarchy;
    }

    private static void initializeAutowiredFields(String pathToJarOrClassesRootFolder,
                                                  AnnotationFinder annotationFinder) throws IOException,
            InvalidClassFileException {

        OfflineInstrumenter offlineInstrumenter = new OfflineInstrumenter();
        offlineInstrumenter.setPassUnmodifiedClasses(true);
        offlineInstrumenter.addInputElement(new File(pathToJarOrClassesRootFolder), pathToJarOrClassesRootFolder);
        String outputJar = getOutputJarPath();
        offlineInstrumenter.setOutputJar(new File(outputJar));
        offlineInstrumenter.beginTraversal();

        ClassInstrumenter classInstrumenter;
        while ((classInstrumenter = offlineInstrumenter.nextClass()) != null) {
            initializeAutowiredFieldsForClass(classInstrumenter, annotationFinder);
            if (classInstrumenter.isChanged()) {
                offlineInstrumenter.outputModifiedClass(classInstrumenter);
            }
        }

        offlineInstrumenter.close();
    }

    private static void initializeAutowiredFieldsForClass(ClassInstrumenter classInstrumenter,
                                                          AnnotationFinder annotationFinder) throws
            InvalidClassFileException {
        ClassReader classReader = classInstrumenter.getReader();
        Set<IField> autowiredFields = annotationFinder.getAutowiredFields(classReader.getName());
        if (autowiredFields.isEmpty()) {
            return;
        }

        int indexOfInitMethod = findIndexOfInitMethod(classReader);
        MethodData initMethodData = classInstrumenter.visitMethod(indexOfInitMethod);
        MethodEditor initMethodEditor = new MethodEditor(initMethodData);

        int indexOfReturnInstruction = findIndexOfReturnInstruction(initMethodEditor);
        String nameOfClassUnderInvestigation = Util.makeType(classReader.getName());

        initMethodEditor.beginPass();
        initMethodEditor.insertBefore(indexOfReturnInstruction,
                buildPatchToWireAllFieldsViaIOC(autowiredFields, nameOfClassUnderInvestigation));
        initMethodEditor.applyPatches();
    }

    private static int findIndexOfInitMethod(ClassReader classReader) throws InvalidClassFileException {
        for (int methodIndex = 0; methodIndex < classReader.getMethodCount(); methodIndex++) {
            if (classReader.getMethodName(methodIndex).equals(MethodReference.initAtom.toString())) {
                return methodIndex;
            }
        }
        throw new RuntimeException("No init method found in class " + classReader.getName());
    }

    private static int findIndexOfReturnInstruction(MethodEditor methodEditor) {
        IInstruction[] instructions = methodEditor.getInstructions();
        for (int instructionIndex = 0; instructionIndex < instructions.length; instructionIndex++) {
            if (instructions[instructionIndex] instanceof ReturnInstruction) {
                return instructionIndex;
            }
        }
        throw new RuntimeException("No return instruction found ");
    }

    private static MethodEditor.Patch buildPatchToWireAllFieldsViaIOC(Set<IField> autowiredFields,
                                                                      String nameOfClassUnderInvestigation) {
        return new MethodEditor.Patch() {
            @Override
            public void emitTo(MethodEditor.Output editorOutput) {
                for (IField autowiredField : autowiredFields) {
                    LoadInstruction loadThis = buildLoad0(nameOfClassUnderInvestigation);
                    editorOutput.emit(loadThis);

                    InvokeInstruction invocationOfIocGetter = buildInvocationOfGetter(autowiredField);
                    editorOutput.emit(invocationOfIocGetter);

                    PutInstruction storeField = buildStoreField(autowiredField, nameOfClassUnderInvestigation);
                    editorOutput.emit(storeField);
                }
            }
        };
    }

    private static LoadInstruction buildLoad0(String nameOfClassUnderInvestigation) {
        // ALOAD 0
        return LoadInstruction.make(nameOfClassUnderInvestigation, 0);
    }

    private static InvokeInstruction buildInvocationOfGetter(IField autowiredField) {
        String selector = "()" + autowiredField.getFieldTypeReference().getName().toString() + ";";
        String targetClassName = IocContainerClass.CLASS_NAME + ";";
        String methodName = IocGetter.getPlainMethodName(autowiredField.getFieldTypeReference());
        return InvokeInstruction.make(selector, targetClassName, methodName, InvokeInstruction.Dispatch.STATIC);
    }

    private static PutInstruction buildStoreField(IField autowiredField, String nameOfClassUnderInvestigation) {
        String fieldNameFullyQualified = autowiredField.getName().toString();
        String fieldName = fieldNameFullyQualified.substring(fieldNameFullyQualified.lastIndexOf('.') + 1);
        String fieldType = autowiredField.getFieldTypeReference().getName().toString() + ";";
        return PutInstruction.make(fieldType, nameOfClassUnderInvestigation, fieldName, false);
    }
}
