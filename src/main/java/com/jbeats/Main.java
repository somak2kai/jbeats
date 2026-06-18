package com.jbeats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        String inputPath = null;
        String repoOverride = null;

        for (String arg : args) {
            if (arg.startsWith("--inp=")) {
                inputPath = arg.substring("--inp=".length());
            } else if (arg.startsWith("--repo=")) {
                repoOverride = arg.substring("--repo=".length());
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                return;
            } else if (arg.equals("--version")) {
                System.out.println("jbeats 0.1.0");
                return;
            }
            // Silently ignore JVM flags like -Xms4g, -Xmx, etc.
            // GraalVM native-image handles these at the VM level.
        }

        if (inputPath == null) {
            System.err.println("error: --inp=<file.java> is required");
            printUsage();
            System.exit(1);
        }

        Path filePath = Paths.get(inputPath).toAbsolutePath().normalize();
        if (!Files.exists(filePath)) {
            System.err.println("error: file not found: " + filePath);
            System.exit(1);
        }
        if (!filePath.toString().endsWith(".java")) {
            System.err.println("error: not a .java file: " + filePath);
            System.exit(1);
        }

        // Determine repo name and relative path
        String repoName;
        String relativePath;
        if (repoOverride != null) {
            repoName = repoOverride;
            relativePath = filePath.getFileName().toString();
        } else {
            Path gitRoot = findGitRoot(filePath.getParent());
            if (gitRoot != null) {
                repoName = gitRoot.getFileName().toString();
                relativePath = gitRoot.relativize(filePath).toString();
            } else {
                // No git root found — use parent directory name
                repoName = filePath.getParent().getFileName().toString();
                relativePath = filePath.getFileName().toString();
            }
        }

        // Parse the file
        Parser.FileResult result;
        try {
            result = Parser.parseFile(filePath);
        } catch (Exception e) {
            System.err.println("error: failed to parse " + filePath + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // Fix file path in result to be relative
        // result.file = relativePath;

        // Build output path: $TMPDIR/beats/<repo>/<relative-dir>/File-beats.json
        Path outputPath = buildOutputPath(repoName, relativePath);

        // Write JSON
        try {
            Files.createDirectories(outputPath.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(result);
            Files.writeString(outputPath, json);
            System.out.println(outputPath);
        } catch (IOException e) {
            System.err.println("error: failed to write output: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Walks up from dir looking for a .git directory.
     * Returns the directory containing .git, or null.
     */
    private static Path findGitRoot(Path dir) {
        Path current = dir;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Builds: $TMPDIR/beats/<repo>/<relative-path-without-.java>-beats.json
     */
    private static Path buildOutputPath(String repoName, String relativePath) {
        String tmpDir = System.getProperty("java.io.tmpdir");
        // Strip .java extension, add -beats.json
        String baseName = relativePath;
        if (baseName.endsWith(".java")) {
            baseName = baseName.substring(0, baseName.length() - ".java".length());
        }
        String outputFile = baseName + "-beats.json";
        return Paths.get(tmpDir, "beats", repoName, outputFile);
    }

    private static void printUsage() {
        System.err.println("Usage: jbeats --inp=<file.java> [--repo=<name>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --inp=<path>   Path to a .java file (required)");
        System.err.println("  --repo=<name>  Override repo name (default: auto-detect from .git)");
        System.err.println("  --version      Print version");
        System.err.println("  --help         Print this help");
    }
}
