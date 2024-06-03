package edu.purdue.cs.toydroid.test;

import com.ibm.wala.examples.drivers.PDFWalaIR;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.util.WalaException;

import java.io.IOException;
import java.util.Properties;

public class WalaTests {

    public static void main(String[] args) throws IOException, WalaException {
//        System.out.println("Path separator: " + File.pathSeparator);
        Properties properties = WalaProperties.loadProperties();
        System.out.println("JDK Library Files: " + properties.getProperty(WalaProperties.J2SE_DIR));
        PDFWalaIR.main(args);
    }
}
