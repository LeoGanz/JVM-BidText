# JVM-BidText
[![DOI](https://zenodo.org/badge/810011768.svg)](https://doi.org/10.5281/zenodo.14034414)

JVM-BidText is a static analysis tool for JVM applications that aims to detect potential sensitive data exposures.
We use bidirectional text correlation analysis which allows us to detect leaks that other tools cannot find.
JVM-BidText is based on [BidText](https://bitbucket.org/hjjandy/toydroid.bidtext/) which introduces the concept of bidirectional text correlation analysis for Android applications.
JVM-BidText is an adapted and generalized version of BidText that supports general JVM projects (Java, Kotlin, etc.) and has basic support for Spring applications.



## Requirements
We tested JVM-BidText on Linux Mint 21.2 with BellSoft Liberica 21.0.1 (Java 21).
We expect that JVM-BidText works on systems with Java 11 - 23, but make no guarantees.
Especially the analysis engine [WALA](https://github.com/wala/WALA) is decisive for the compatibility; we use WALA 1.6.7.

JVM-BidText has no specific hardware requirements. Memory consumption depends on the size of the analyzed project and inclusions but typically stays in the order of a few hundred MB. Runtimes are in the order of seconds even for projects with a few kLoC.



## Execution
Compile the target application; JVM-BidText operates on the compiled bytecode.
Run the analysis with the following command:
```bash
./gradlew run --args=<path-to-compiled-classes>
```
Reports are generated in the `reports` directory together with the log of the analysis run.




## Configuration
All configuration files are located in the `res` directory.
General configuration can be changed through the `Config.properties` file.
The config file allows you to select which other files with rules etc. the analysis will use.
Further, you can configure certain options for the analysis; we explain the options inside the file.


## Reproduction of the Evaluation Results

The following sections describe how to reproduce the results of the case studies presented in the thesis.
We describe the required changes to the configuration and how to run the analysis on the respective projects.
When we performed the evaluation for the case studies in section 4 of the thesis, the spring extension was not yet implemented.
This version of JVM-BidText is available in the branch `jvm-no-spring`.
In the design of the extension for Spring, we made sure that the extension can be disabled.
If the extension is disabled, the analysis will completely skip the Spring preprocessing step and run as it would without the extension.
Therefore, you can replicate the results of the case studies from the `spring` branch by disabling the Spring extension in the configuration.
As the `spring` branch is our main branch, this has the benefit of keeping access to a proper configuration system and this README file.
We recommend staying in the `spring` branch.

We provide links to the repositories of the case studies and test cases in the following sections.
Additionally, an all-in-one archive with all case studies and test cases is available at [https://doi.org/10.5281/zenodo.14033511](https://doi.org/10.5281/zenodo.14033511).


### Evaluation on LeakyCode (Case Study on Hand-Crafted Examples)
No changes to default configuration required.

You can compile the [LeakyCode](https://github.com/LeoGanz/LeakyCode) project with the following command executed in the root of the LeakyCode project:
```bash
./mvnw compile
```

Copy the path to the class files: `<LeakyCode-Project-Root>/target/classes`\
Execute the analysis like described in the Execution section.

Alternatively, you can execute the analysis as a suite by using gradle task `runSuite`:
```bash
./gradlew runSuite --args=<path-to-compiled-classes>
```
This will run the analysis on all the examples in the subfolders of the LeakyCode project in separate processes.
The generated `reports` directory for each example is copied into the subfolder of the example, i.e., next to the class files.



### Evaluation on alpaca-java (Case Study on Real-World Project)
To evaluate the tool on the alpaca-java project, changes to the in- and exclusions are required.
You can specify the corresponding files in the `Config.properties` file.
You will need to adapt the `Inclusions_alpaca.properties` to adapt the paths to the required libraries for your system.
You can obtain the respective paths by adding the following gradle task to the `build.gradle` file of the alpaca-java project:
```gradle
    tasks.register('printRuntimeClasspath') {  
        doFirst {  
            sourceSets.main.runtimeClasspath.each { println it}  
        }
    }
```
If you download the [alpaca-java project, from our fork](https://github.com/LeoGanz/alpaca-java), the `build.gradle` file is already adapted.
Run the task with `./gradlew printRuntimeClasspath` from the root of the alpaca-java repo.
Copy the output only for the libraries gson, okttp-jvm, and okio-jvm to the `Inclusions_alpaca.properties` file.

Make sure to configure the following options in the `Config.properties` file:
```properties
PREFIXES_OF_CALLBACK_METHODS=on
USE_WORKAROUND_FOR_ABSTRACT=true
```


You can compile the alpaca-java project (downloaded from our fork) with the following command executed in the root of the alpaca-java project:
```bash
./gradlew build
```

Copy the path to the class files: `<alpaca-java-Project-Root>/build/classes`\
Execute the analysis like described in the Execution section.

To reproduce the results of the evaluation, you can enable or disable the options `USE_WORKAROUND_FOR_ABSTRACT` in the `Config.properties` file.
Further, you can enable or disable the additional propagation rule in `ApiPropagationRules.txt` to see the effect of the rule on the results; the rule is clearly marked in the file.


### Evaluation on LeakyKotlin (case study on Kotlin Application)
No changes to default configuration required.

You can compile the [LeakyKotlin](https://github.com/LeoGanz/LeakyKotlin) project with the following command executed in the root of the LeakyKotlin project:
```bash
./gradlew build
```

Copy the path to the class files: `<LeakyKotlin-Project-Root>/build/classes`\
Execute the analysis like described in the Execution section.



### Evaluation on LeakySprings (Case Study on Spring Application)
Make sure to configure the following options in the `Config.properties` file:
```properties
SPRING_PREPROCESSING_ENABLED=true
PREFIXES_OF_CALLBACK_METHODS=on
USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT=true
```
If you set the option `SPRING_PREPROCESSING_ENABLED` to `false`, you can replicate the results of the evaluation without the Spring extension.
Setting the option `ENABLE_SPRING_ENTRYPOINT_DISCOVERY` to `false`, has no influence on the results of the evaluation of the case study, because the entry points of the CaseStudyController also follow the naming convention of the entry points.
Either `USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT` or `ENABLE_SPRING_ENTRYPOINT_DISCOVERY` is required to find the entry points of the CaseStudyController.
In general, `ENABLE_SPRING_ENTRYPOINT_DISCOVERY` is the preferred option, as it does not rely on naming conventions.

You can compile the [LeakySprings](https://github.com/LeoGanz/LeakySprings) project with the following command executed in the root of the LeakySprings project:
```bash
./mvnw compile
```

Copy the path to the class files:`<LeakySprings-Project-Root>/target/classes`\
Make sure not to specify any subfolder of the classes directory. The bytecode instrumentation will not be able to build a working jar file with the modified code if it receives something else than the root of the classes directory.\
Execute the analysis like described in the Execution section.
To measure runtime and memory consumption for the LeakySprings project, we deleted all compiled class files not belonging to the case study from the target directory.



### Comparison to CodeQL
To compare the results of JVM-BidText to CodeQL, you can enable CodeQL with the extended query suite in a public GitHub repository.
We provide the additional test cases for the comparison in the repository [CodeQL-Tests](https://github.com/LeoGanz/CodeQL-Tests).
We use the term "password" to indicate the sensitive data in the test cases.
This term is known to work with CodeQL.
Make sure to configure the keywords of JVM-BidText to recognize this term as weill.
The simplest option is to enable the following setting in `Config.properties`:
```properties
SENSITIVE_TERMS=SensitiveTerms_ComparisonCodeQL.txt
```


## Publications
I make my Master's thesis "Detection of Sensitive Data Exposures in JVM Applications with Bidirectional Text Correlation Analysis" available at [https://doi.org/10.5281/zenodo.14033420](https://doi.org/10.5281/zenodo.14033420).

Huang, Zhang, and Tan introduce BidText in their paper "Detecting Sensitive Data Disclosure via Bi-directional Text Correlation Analysis" available at [https://doi.org/10.1145/2950290.2950348](https://doi.org/10.1145/2950290.2950348).
