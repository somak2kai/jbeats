package com.jbeats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point for jbeats.
 *
 * Usage: jbeats --inp=path/to/File.java [--repo=repo-name]
 *
 * Parses the Java file and writes a -beats.json file to:
 * $TMPDIR/beats/<repo-name>/<relative-path>/File-beats.json
 */
public class Main {

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> inputs = new ArrayList<>();
        String repo = null;

        for (String arg : args) {
            if (arg.startsWith("--inp=")) {
                inputs.add(arg.substring("--inp=".length()));
            } else if (arg.startsWith("--repo=")) {
                repo = arg.substring("--repo=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage(err);
                return 0;
            } else if (arg.equals("--version")) {
                out.println("jbeats " + Version.VALUE);
                return 0;
            }
            // Silently ignore JVM flags like -Xms4g, -Xmx, etc.
            // GraalVM native-image handles these at the VM level.
        }

        if (inputs.isEmpty()) {
            err.println("error: --inp=<file.java> is required");
            printUsage(err);
            return 1;
        }

        Path repoDir = Paths.get(repo != null ? repo : "").toAbsolutePath().normalize();
        Path repoName = repoDir.getFileName();
        String repoNameStr = repoName != null ? repoName.toString() : "root";
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        List<String> results = new ArrayList<>(inputs.size());
        boolean anyFailed = false;
        for (String inp : inputs) {
            try {
                results.add(processFile(repoDir.resolve(inp).normalize(), repoNameStr, gson).toString());
            } catch (Exception e) {
                err.println("error: " + inp + ": " + e.getMessage());
                results.add("");
                anyFailed = true;
            }
        }

        results.forEach(out::println);
        return anyFailed ? 1 : 0;
    }

    private static Path processFile(Path filePath, String repoName, Gson gson) throws Exception {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("file not found: " + filePath);
        }
        if (!filePath.toString().endsWith(".java")) {
            throw new IllegalArgumentException("not a .java file: " + filePath);
        }
        Parser.FileResult result = Parser.parseFile(filePath);
        Path outputPath = buildOutputPath(repoName, filePath.getFileName().toString());
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, gson.toJson(result));
        return outputPath;
    }

    private static Path buildOutputPath(String repoName, String fileName) {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String baseName = fileName.substring(0, fileName.length() - ".java".length());
        String outputFile = java.util.UUID.randomUUID() + "-" + baseName + "-beats.json";
        return Paths.get(tmpDir, "beats", repoName, outputFile);
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage: jbeats --repo=<dir> --inp=<file.java> [--inp=<file.java> ...]");
        err.println();
        err.println("Options:");
        err.println("  --inp=<path>   .java file relative to --repo (repeatable, required)");
        err.println("  --repo=<dir>   Repo root that --inp paths are relative to (default: cwd)");
        err.println("  --version      Print version");
        err.println("  --help         Print this help");
    }
}
