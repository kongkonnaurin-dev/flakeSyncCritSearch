/*
The MIT License (MIT)
Copyright (c) 2025 Nandita Jayanthi
Copyright (c) 2025 Shanto Rahman
Copyright (c) 2025 August Shi

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package flakesync;

import flakesync.common.ConfigurationDefaults;
import flakesync.common.Level;
import flakesync.common.Logger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

@Mojo(name = "delaylocs", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class RunWithDelaysMojo extends FlakeSyncAbstractMojo {

    int[] delays = {100, 200, 400, 800, 1600, 3200, 6400, 12800};

    // ---------- PROFILING COUNTERS ----------
    private long startTime;
    private int delayIterations = 0;
    private int testExecutions = 0;
    private int finalDelay = 0;
    private boolean testFailed = false;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();
        startTime = System.nanoTime();  // start timer
        Logger.getGlobal().log(Level.INFO, ("Running RunWithDelaysMojo"));

        createWhiteList();

        for (int i = 0; i < delays.length; i++) {
            delayIterations++;  // count each delay level tried
            testExecutions++;   // each iteration runs the test once
            finalDelay = delays[i];

            SurefireExecution cleanExec = SurefireExecution.SurefireFactory.createDelayAllExec(
                    this.surefire, this.originalArgLine, this.mavenProject,
                    this.mavenSession, this.pluginManager,
                    Paths.get(this.baseDir.getAbsolutePath(), ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                    this.localRepository, this.testName, delays[i]);

            try {
                this.executeSurefireExecution(null, cleanExec);
                // If we get here, test passed (no exception), continue to next delay
            } catch (MojoExecutionException mee) {
                // Test failed! This is what we want - we found a delay that causes failure
                testFailed = true;
                finalDelay = delays[i];
                break;
            } catch (Throwable exception) {
                System.out.println("Surefire Execution failed: " + exception);
                testFailed = true;
                finalDelay = delays[i];
                break;
            }
        }

        // ---------- PRINT CLEAN SUMMARY ----------
        long endTime = System.nanoTime();
        System.out.println("\n========== DELAYLOCS RESULTS ==========");
        System.out.println("Total time: " + (endTime - startTime) / 1_000_000 + " ms");
        System.out.println("Delay iterations tried: " + delayIterations);
        System.out.println("Test executions: " + testExecutions);
        System.out.println("Final delay that caused failure: " + finalDelay + " ms");
        System.out.println("Test eventually failed: " + (testFailed ? "YES" : "NO"));
        System.out.println("=========================================");
    }

    private MojoExecutionException executeSurefireExecution(MojoExecutionException allExceptions,
                                                            SurefireExecution execution) throws MojoExecutionException,
            Throwable {
        execution.run();
        return allExceptions;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    private boolean createWhiteList() {
        System.out.println("Inside createWhiteList");

        File whitelist = new File(
                String.valueOf(Paths.get(this.baseDir.toString(),
                        Constants.getWhitelistFilepath(this.testName).toString())));

        File outputDir = new File(this.mavenProject.getBuild().getOutputDirectory());

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(whitelist))) {

            Path outputPath = outputDir.toPath();
            if (Files.exists(outputPath)) {
                try (Stream<Path> paths = Files.walk(outputPath)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".class"))
                            .filter(path -> !path.toString().contains("Tests"))
                            .map(path -> outputPath.relativize(path)) // relative to outputDir
                            .map(Path::toString)
                            .map(s -> s.replace(File.separatorChar, '.')) // convert to package style
                            .map(s -> s.replaceAll("\\.class$", ""))
                            .forEach(className -> {
                                try {
                                    bw.write(className);
                                    bw.newLine();
                                } catch (IOException ioe) {
                                    System.out.println("Exception when creating whitelist: " + ioe);
                                }
                            });
                }
            }

            String classpath = System.getProperty("java.class.path");
            String[] jars = classpath.split(File.pathSeparator);

            for (String jarPath : jars) {
                if (jarPath.endsWith(".jar")) {
                    try (JarFile jarFile = new JarFile(jarPath)) {
                        Enumeration<JarEntry> entries = jarFile.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                                String className = entry.getName()
                                        .replace('/', '.')
                                        .replaceAll("\\.class$", "");
                                bw.write(className);
                                bw.newLine();
                            }
                        }
                    } catch (IOException ioe) {
                        System.err.println("Failed to read JAR: " + jarPath);
                    }
                }
            }

            bw.flush();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        return true;
    }
}