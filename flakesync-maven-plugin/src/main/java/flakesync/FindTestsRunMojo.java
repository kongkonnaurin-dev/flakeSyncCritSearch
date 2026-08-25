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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;

@Mojo(name = "concurrentfind", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class FindTestsRunMojo extends FlakeSyncAbstractMojo {

    // ---------- PROFILING COUNTERS ----------
    // Per Section 3.1.1 of the FlakeSync paper: "we run the test 10 times and
    // combine all the concurrent methods detected throughout those runs."
    private static final int CONCURRENT_METHOD_RUNS = 10;

    private long startTime;
    private long testRunsTime = 0L;
    private long countingTime = 0L;
    private int testRuns = 0;
    private int concurrentMethodsFound = 0;
    private int delayLocationsFound = 0;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();
        startTime = System.nanoTime();
        Logger.getGlobal().log(Level.INFO, ("Running FindTestsRunMojo"));

        try {
            // --- REPEATED TEST EXECUTION PHASE ---
            // This runs the test 10 times to find concurrent methods,
            // as described in the paper.
            long runsStart = System.nanoTime();
            for (int i = 0; i < CONCURRENT_METHOD_RUNS; i++) {
                testRuns++;
                SurefireExecution cleanExec = SurefireExecution.SurefireFactory.createConcurrentMethodsExec(
                        this.surefire, this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                        Paths.get(this.baseDir.getAbsolutePath(),
                                ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                        this.testName, this.localRepository);
                this.executeSurefireExecution(null, cleanExec);
            }
            testRunsTime += (System.nanoTime() - runsStart);
            // --- END REPEATED TEST EXECUTION PHASE ---

            // --- RESULT COUNTING PHASE ---
            // Reading back the concurrent-methods and delay-locations result files.
            long countStart = System.nanoTime();
            concurrentMethodsFound = countConcurrentMethods();
            delayLocationsFound = countDelayLocations();
            countingTime += (System.nanoTime() - countStart);
            // --- END RESULT COUNTING PHASE ---

        } catch (Throwable exception) {
            System.out.println("Error executing test: The test did not run");
            exception.printStackTrace();
        }

        // ---------- PRINT CLEAN SUMMARY ----------
        long endTime = System.nanoTime();
        System.out.println("\n========== CONCURRENTFIND PROFILING SUMMARY ==========");
        System.out.printf("Total time (ms): %d%n", (endTime - startTime) / 1_000_000);
        System.out.printf("Repeated test-execution time (ms): %d%n", testRunsTime / 1_000_000);
        System.out.printf("Result-counting time (ms): %d%n", countingTime / 1_000_000);
        System.out.printf("Test runs: %d%n", testRuns);
        System.out.printf("Concurrent methods found: %d%n", concurrentMethodsFound);
        System.out.printf("Delay locations found: %d%n", delayLocationsFound);
        System.out.println("========================================================");
    }

    private MojoExecutionException executeSurefireExecution(MojoExecutionException allExceptions,
                                                            SurefireExecution execution)
            throws Throwable {
        try {
            execution.run();
        } catch (MojoExecutionException ex) {
            return (MojoExecutionException) Utils.linkException(ex, allExceptions);
        }
        return allExceptions;
    }

    private int countConcurrentMethods() {
        int count = 0;
        try {
            File file = new File(this.baseDir, ".flakesync/" + testName.replace("#", ".") + "-ResultMethods.txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine();
                while (line != null) {
                    if (!line.trim().isEmpty() && !line.startsWith("#")) {
                        count++;
                    }
                    line = reader.readLine();
                }
                reader.close();
            } else {
                File altFile = new File(this.baseDir, ".flakesync/" + testName + "-ResultMethods.txt");
                if (altFile.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(altFile));
                    String line = reader.readLine();
                    while (line != null) {
                        if (!line.trim().isEmpty() && !line.startsWith("#")) {
                            count++;
                        }
                        line = reader.readLine();
                    }
                    reader.close();
                }
            }
        } catch (IOException ioe) {
            System.out.println("Could not read ResultMethods file: " + ioe);
        }
        return count;
    }

    private int countDelayLocations() {
        int count = 0;
        try {
            File file = new File(String.valueOf(Paths.get(String.valueOf(this.baseDir),
                    String.valueOf(Constants.getAllLocationsFilepath(testName)))));
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line = reader.readLine(); // Skip first line (delay value)
                line = reader.readLine();
                while (line != null) {
                    if (!line.trim().isEmpty()) {
                        count++;
                    }
                    line = reader.readLine();
                }
                reader.close();
            }
        } catch (IOException ioe) {
            System.out.println("Could not read Locations file: " + ioe);
        }
        return count;
    }
}