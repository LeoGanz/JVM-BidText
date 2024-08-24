package edu.purdue.cs.toydroid.bidtext.java.spring;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrike.shrikeBT.*;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.ClassInstrumenter;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.OfflineInstrumenter;
import com.ibm.wala.shrike.shrikeCT.ClassReader;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.MethodReference;
import edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container.IocContainerClass;
import edu.purdue.cs.toydroid.bidtext.java.spring.ioc_container.IocGetter;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class IocInjector {

    private static final String INSTRUMENTED_JAR_FILE_NAME = "bidtext-simulated-ioc.jar";

    public static void doInjection(String pathToSystemUnderTest, ClassHierarchy classHierarchy, AnalysisScope scope,
                                   AnalysisCache cache) throws InvalidClassFileException, IOException {
        AnnotationFinder annotationFinder = new AnnotationFinder(classHierarchy);
        annotationFinder.processClasses();

        IocContainerClass springIOCModel =
                IocContainerClass.make(annotationFinder, classHierarchy, new AnalysisOptions(scope, Set.of()), cache);
        classHierarchy.addClass(springIOCModel);

        initializeAutowiredFields(pathToSystemUnderTest, annotationFinder);
    }

    private static void initializeAutowiredFields(String pathToSystemUnderTest,
                                                  AnnotationFinder annotationFinder) throws IOException,
            InvalidClassFileException {

        OfflineInstrumenter offlineInstrumenter = new OfflineInstrumenter();
        offlineInstrumenter.setPassUnmodifiedClasses(true);
        offlineInstrumenter.addInputElement(new File(pathToSystemUnderTest), pathToSystemUnderTest);
        String tmpDir = System.getProperty("java.io.tmpdir");
        String outputJar = tmpDir + File.separator + INSTRUMENTED_JAR_FILE_NAME;
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

                    PutInstruction storeField =
                            buildStoreField(autowiredField, nameOfClassUnderInvestigation);
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
        String fieldName = autowiredField.getName().toString();
        String fieldType = autowiredField.getFieldTypeReference().getName().toString() + ";";
        return PutInstruction.make(fieldType, nameOfClassUnderInvestigation, fieldName, false);
    }
}
