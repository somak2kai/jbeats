package com.jbeats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @TempDir
    Path repo;

    @Test
    void multipleInputsPrintPathsInOrderAndContinuePastFailures() throws Exception {
        Files.createDirectories(repo.resolve("src/a"));
        Files.writeString(repo.resolve("src/a/A.java"), "class A { void x() {} }");
        Files.writeString(repo.resolve("Bad.java"), "class Bad { void x( }");
        Files.writeString(repo.resolve("B.java"), "class B { void y() {} }");

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {
                "--repo=" + repo, "--inp=src/a/A.java", "--inp=Bad.java", "--inp=Missing.java", "--inp=B.java"
        }, new PrintStream(out), new PrintStream(err));

        String[] lines = out.toString().split("\n", -1);
        assertEquals(5, lines.length); // 4 lines + trailing newline
        assertTrue(lines[0].endsWith("-A-beats.json"), lines[0]);
        assertEquals("", lines[1]);
        assertEquals("", lines[2]);
        assertTrue(lines[3].endsWith("-B-beats.json"), lines[3]);
        assertTrue(Files.exists(Path.of(lines[0])));
        assertTrue(Files.exists(Path.of(lines[3])));
        assertTrue(err.toString().contains("Bad.java"));
        assertTrue(err.toString().contains("Missing.java"));
        assertEquals(1, code);
    }
}
