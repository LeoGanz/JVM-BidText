# JVM-BidText
JvM-BidText is a static analysis tool for JVM projects that aims to detect potential sensitive data exposures.
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
General configuration can be changed through the `Config.properties` file.
The config file allows you to select which other files with rules etc. the analysis will use.
Further, you can configure certain options for the analysis; we explain the options inside the file.


## Reproduction of the Evaluation Results

The following sections describe how to reproduce the results of the case studies presented in the thesis.
We describe the required changes to the configuration and how to run the analysis on the respective projects.


### Evaluation on LeakyCode (Case Study on Hand-Crafted Examples)
No changes to default configuration required.

You can compile the LeakyCode project with the following command executed in the root of the LeakyCode project:
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
If you download the alpaca-java project, from our fork, the `build.gradle` file is already adapted.
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


### Evaluation on LeakySprings (Case Study on Spring Application)
Make sure to configure the following options in the `Config.properties` file:
```properties
SPRING_PREPROCESSING_ENABLED=true
PREFIXES_OF_CALLBACK_METHODS=on
USE_ANY_METHOD_WITH_PREFIX_AS_ENTRYPOINT=true
```

You can compile the LeakySprings project with the following command executed in the root of the LeakySprings project:
```bash
./mvnw compile
```

Copy the path to the class files:`<LeakySprings-Project-Root>/target/classes`\
Execute the analysis like described in the Execution section.


### Evaluation on LeakyKotlin (Footnote on Kotlin Support)
No changes to default configuration required.

You can compile the LeakyKotlin project with the following command executed in the root of the LeakyKotlin project:
```bash
./gradlew build
```

Copy the path to the class files: `<LeakyKotlin-Project-Root>/target/classes`\
Execute the analysis like described in the Execution section.