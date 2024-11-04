package de.lmu.ifi.jvmbidtext;

import de.lmu.ifi.jvmbidtext.analysis.AnalysisUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class SuiteMain {

    /**
     * Run analysis on all test apps in the given root test directory. Reports (log and sink descriptions) are saved in
     * the test app's directory.
     *
     * @param args first arg should be folder that contains subfolders for all test apps, possibly nested
     * @throws IOException if there is an error reading or writing files
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java -jar SuiteMain.jar <path-to-test-apps>");
        }
        String testAppsFolder = args[0];
        Path directoryRoot = Path.of(testAppsFolder);
        if (!directoryRoot.toFile().exists()) {
            throw new IllegalArgumentException("Directory does not exist: " + directoryRoot);
        }
        if (!directoryRoot.toFile().isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + directoryRoot);
        }
        System.out.println("Test apps folder: " + testAppsFolder);

        try (Stream<Path> paths = Files.walk(directoryRoot)) {
            paths.filter(Files::isDirectory).filter(SuiteMain::isDeepestLevel).forEach(SuiteMain::runAnalysis);
        }
    }

    private static boolean isDeepestLevel(Path path) {
        if (path.endsWith(AnalysisUtil.REPORT_FOLDER)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.allMatch(subpath ->
                    subpath.toFile().getAbsolutePath().endsWith(".java") ||
                            subpath.toFile().getAbsolutePath().endsWith(".class") ||
                            Objects.equals(path, subpath) ||
                            path.relativize(subpath).startsWith(AnalysisUtil.REPORT_FOLDER));
        } catch (IOException e) {
            return false;
        }
    }

    private static void runAnalysis(Path target) {
        System.out.println("--------------------------------------------------");
        System.out.println("Running analysis on: " + target);
        try {
            execJavaProcess(Main.class, List.of(target.toString()));
            clearReportFolderFromTarget(target);
            copyReport(target);
        } catch (Throwable e) {
            // Exceptions from analysis already logged by Main
            // Exceptions from copying report are unexpected and should be rethrown
            throw new RuntimeException(e);
        }
    }

    private static void copyReport(Path target) throws IOException {
        Path reportDir = Path.of(AnalysisUtil.REPORT_FOLDER);
        Path reportInTarget = target.resolve(AnalysisUtil.REPORT_FOLDER);
        System.out.println("Copying report to: " + reportInTarget);
        copyFolderRecursive(reportDir, reportInTarget);
    }

    private static void clearReportFolderFromTarget(Path target) throws IOException {
        Path reportInTarget = target.resolve(AnalysisUtil.REPORT_FOLDER);
        if (!reportInTarget.toFile().exists()) {
            return;
        }
        try (Stream<Path> pathStream = Files.walk(reportInTarget)) {
            pathStream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * <a href="https://stackoverflow.com/questions/29076439/java-8-copy-directory-recursively/60621544#60621544">Adapted from this Stackoverflow Answer</a>
     */
    public static void copyFolderRecursive(Path source, Path target, CopyOption... options)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), options);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * <a href="https://stackoverflow.com/a/723914">Adapted from this Stackoverflow Answer</a>
     */
    public static int execJavaProcess(Class<?> klass, List<String> args) throws IOException,
            InterruptedException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome +
                File.separator + "bin" +
                File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String className = klass.getName();

        List<String> command = new LinkedList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        command.add(className);
        if (args != null) {
            command.addAll(args);
        }

        ProcessBuilder builder = new ProcessBuilder(command);

        Process process = builder.inheritIO().start();
        process.waitFor();
        return process.exitValue();
    }

}
