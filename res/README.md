# JVM-BidText
JvM-BidText is a static analysis tool for JVM projects that aims to detect potential sensitive data exposures.
We use bidirectional text correlation analysis which allows us to detect leaks that other tools cannot find.
JVM-BidText is based on [BidText](https://bitbucket.org/hjjandy/toydroid.bidtext/) which introduces the concept of bidirectional text correlation analysis for Android applications.
JVM-BidText is an adapted and generalized version of BidText that supports general JVM projects (Java, Kotlin, etc.) and has basic support for Spring applications.

## Configuration

General configuration can be changed through the `Config.properties` file.
The config file allows you to select which other files with rules etc. the analysis will use.

### Evaluation on alpaca-java
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
