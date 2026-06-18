package com.jbeats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @TempDir
    Path tempDir;

    /** Write source to a temp .java file and parse it. */
    private Parser.FileResult parse(String source) throws Exception {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, source);
        return Parser.parseFile(file);
    }

    @Test
    void extractsMethodNames() throws Exception {
        var result = parse("""
                public class Foo {
                    public void alpha() {}
                    private int beta() { return 1; }
                    protected String gamma() { return "x"; }
                }
                """);
        var names = result.functions.stream().map(f -> f.name).toList();
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        assertTrue(names.contains("gamma"));
    }

    @Test
    void extractsConstructor() throws Exception {
        var result = parse("""
                public class Foo {
                    private int x;
                    public Foo(int x) { this.x = x; }
                    public void bar() {}
                }
                """);
        var names = result.functions.stream().map(f -> f.name).toList();
        assertTrue(names.contains("Foo"));
        assertTrue(names.contains("bar"));
    }

    @Test
    void abstractMethodSkipped() throws Exception {
        var result = parse("""
                public abstract class Foo {
                    public abstract void bar();
                    public void baz() {}
                }
                """);
        // abstract method has no body — should not appear
        var names = result.functions.stream().map(f -> f.name).toList();
        assertFalse(names.contains("bar"));
        assertTrue(names.contains("baz"));
    }

    @Test
    void lineRangeIsPopulated() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        int x = 1;
                    }
                }
                """);
        var fn = result.functions.get(0);
        assertTrue(fn.start_line > 0);
        assertTrue(fn.end_line >= fn.start_line);
        assertTrue(fn.line_count >= 1);
    }

    // ── Token sequences ──────────────────────────────────────────────

    @Test
    void ifStatementEmitsToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar(int x) {
                        if (x > 0) { return; }
                    }
                }
                """);
        var tokens = result.functions.get(0).token_seq;
        assertTrue(tokens.contains(Parser.TK_IF));
        assertTrue(tokens.contains(Parser.TK_RETURN));
    }

    @Test
    void regularForLoopEmitsToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        for (int i = 0; i < 10; i++) {}
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_FOR));
    }

    @Test
    void forEachEmitsRangeToken() throws Exception {
        var result = parse("""
                import java.util.List;
                public class Foo {
                    public void bar(List<String> items) {
                        for (String s : items) {}
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_RANGE));
    }

    @Test
    void whileAndDoWhileEmitTokens() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar(int x) {
                        while (x > 0) { x--; }
                        do { x++; } while (x < 5);
                    }
                }
                """);
        var tokens = result.functions.get(0).token_seq;
        assertTrue(tokens.contains(Parser.TK_WHILE));
        assertTrue(tokens.contains(Parser.TK_DO_WHILE));
    }

    @Test
    void tryCatchFinallyEmitsAllTokens() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        try {
                            throw new RuntimeException();
                        } catch (Exception e) {
                            // handle
                        } finally {
                            // cleanup
                        }
                    }
                }
                """);
        var tokens = result.functions.get(0).token_seq;
        assertTrue(tokens.contains(Parser.TK_TRY));
        assertTrue(tokens.contains(Parser.TK_CATCH));
        assertTrue(tokens.contains(Parser.TK_FINALLY));
        assertTrue(tokens.contains(Parser.TK_THROW));
    }

    @Test
    void switchEmitsTokens() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar(int x) {
                        switch (x) {
                            case 1: break;
                            case 2: break;
                            default: break;
                        }
                    }
                }
                """);
        var tokens = result.functions.get(0).token_seq;
        assertTrue(tokens.contains(Parser.TK_SWITCH));
        assertTrue(tokens.contains(Parser.TK_CASE));
        assertTrue(tokens.contains(Parser.TK_BREAK));
    }

    @Test
    void lambdaEmitsFunclitToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        Runnable r = () -> {};
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_FUNCLIT));
    }

    @Test
    void variableDeclarationEmitsAssignToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        int x = 1;
                        String s = "hello";
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_ASSIGN));
    }

    @Test
    void objectCreationEmitsCompositeLitToken() throws Exception {
        var result = parse("""
                import java.util.ArrayList;
                public class Foo {
                    public void bar() {
                        Object o = new ArrayList<>();
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_COMPOSITE_LIT));
    }

    @Test
    void instanceofEmitsTypeAssertToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar(Object o) {
                        if (o instanceof String) {}
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_TYPE_ASSERT));
    }

    @Test
    void castEmitsTypeAssertToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar(Object o) {
                        String s = (String) o;
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_TYPE_ASSERT));
    }

    @Test
    void synchronizedEmitsToken() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        synchronized (this) {}
                    }
                }
                """);
        assertTrue(result.functions.get(0).token_seq.contains(Parser.TK_SYNCHRONIZED));
    }

    @Test
    void continueAndBreakEmitTokens() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {
                        for (int i = 0; i < 10; i++) {
                            if (i == 3) continue;
                            if (i == 7) break;
                        }
                    }
                }
                """);
        var tokens = result.functions.get(0).token_seq;
        assertTrue(tokens.contains(Parser.TK_CONTINUE));
        assertTrue(tokens.contains(Parser.TK_BREAK));
    }

    // ── isTestFile detection ─────────────────────────────────────────

    @Test
    void junit4RegularImportIsTest() throws Exception {
        var result = parse("""
                import org.junit.Test;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void junit4StaticImportIsTest() throws Exception {
        // Static import: imp.getNameAsString() returns "org.junit.Assert.assertTrue"
        // Prefix match on "org.junit" must catch this.
        var result = parse("""
                import static org.junit.Assert.assertTrue;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void junit4AssertStaticImportIsTest() throws Exception {
        var result = parse("""
                import static org.junit.Assert.assertEquals;
                import static org.junit.Assert.assertNotNull;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void junit5IsTest() throws Exception {
        var result = parse("""
                import org.junit.jupiter.api.Test;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void junit5StaticImportIsTest() throws Exception {
        var result = parse("""
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void testngIsTest() throws Exception {
        var result = parse("""
                import org.testng.annotations.Test;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void mockitoIsTest() throws Exception {
        var result = parse("""
                import org.mockito.Mockito;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void mockitoStaticImportIsTest() throws Exception {
        var result = parse("""
                import static org.mockito.Mockito.when;
                import static org.mockito.Mockito.verify;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void powerMockIsTest() throws Exception {
        var result = parse("""
                import org.powermock.api.mockito.PowerMockito;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void powerMockStaticImportIsTest() throws Exception {
        var result = parse("""
                import static org.powermock.api.mockito.PowerMockito.mockStatic;
                public class FooTest {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void cucumberModernIsTest() throws Exception {
        var result = parse("""
                import io.cucumber.java.en.Given;
                import io.cucumber.java.en.When;
                public class StepDefs {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void cucumberLegacyApiIsTest() throws Exception {
        var result = parse("""
                import cucumber.api.java.en.Given;
                public class StepDefs {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void cucumberRuntimeIsTest() throws Exception {
        var result = parse("""
                import cucumber.runtime.Backend;
                public class StepDefs {
                    public void foo() {}
                }
                """);
        assertTrue(result.functions.get(0).test_code);
    }

    @Test
    void nonTestFileIsNotTest() throws Exception {
        var result = parse("""
                import java.util.List;
                import java.util.Map;
                public class Foo {
                    public void bar() {}
                }
                """);
        assertFalse(result.functions.get(0).test_code);
    }

    // ── Generated code detection ─────────────────────────────────────

    @Test
    void generatedAnnotationDetected() throws Exception {
        var result = parse("""
                import javax.annotation.Generated;
                @Generated("some-tool")
                public class Foo {
                    public void bar() {}
                }
                """);
        assertTrue(result.functions.get(0).generated_code);
    }

    @Test
    void generatedCommentDetected() throws Exception {
        var result = parse("""
                // Code generated by protoc. DO NOT EDIT.
                public class Foo {
                    public void bar() {}
                }
                """);
        assertTrue(result.functions.get(0).generated_code);
    }

    @Test
    void autoGeneratedCommentDetected() throws Exception {
        var result = parse("""
                /* AUTO-GENERATED FILE. DO NOT MODIFY. */
                public class Foo {
                    public void bar() {}
                }
                """);
        assertTrue(result.functions.get(0).generated_code);
    }

    @Test
    void normalClassNotGenerated() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {}
                }
                """);
        assertFalse(result.functions.get(0).generated_code);
    }

    // ── Trivial constructor detection ────────────────────────────────

    @Test
    void trivialConstructorFieldAssignmentsOnly() throws Exception {
        var result = parse("""
                public class Foo {
                    private int x;
                    private String y;
                    public Foo(int x, String y) {
                        this.x = x;
                        this.y = y;
                    }
                }
                """);
        // is_constructor == true means "trivial constructor"
        var ctor = result.functions.stream().filter(f -> f.name.equals("Foo")).findFirst().orElseThrow();
        assertTrue(ctor.is_constructor);
    }

    @Test
    void emptyConstructorIsTrivial() throws Exception {
        var result = parse("""
                public class Foo {
                    public Foo() {}
                }
                """);
        var ctor = result.functions.stream().filter(f -> f.name.equals("Foo")).findFirst().orElseThrow();
        assertTrue(ctor.is_constructor);
    }

    @Test
    void constructorWithLogicIsNotTrivial() throws Exception {
        var result = parse("""
                public class Foo {
                    private int x;
                    public Foo(int x) {
                        this.x = x;
                        System.out.println("init");
                    }
                }
                """);
        var ctor = result.functions.stream().filter(f -> f.name.equals("Foo")).findFirst().orElseThrow();
        assertFalse(ctor.is_constructor);
    }

    @Test
    void constructorWithSuperCallIsTrivial() throws Exception {
        var result = parse("""
                public class Bar extends Object {
                    public Bar() { super(); }
                }
                """);
        var ctor = result.functions.stream().filter(f -> f.name.equals("Bar")).findFirst().orElseThrow();
        assertTrue(ctor.is_constructor);
    }

    // ── Call targets ─────────────────────────────────────────────────

    @Test
    void staticCallTargetExtracted() throws Exception {
        var result = parse("""
                import java.util.Collections;
                import java.util.List;
                public class Foo {
                    public void bar(List<String> items) {
                        Collections.sort(items);
                    }
                }
                """);
        var targets = result.functions.get(0).call_targets;
        assertTrue(targets.stream().anyMatch(t -> t.equals("java.util.Collections.sort")));
    }

    @Test
    void multipleCallTargetsExtracted() throws Exception {
        var result = parse("""
                import java.util.Collections;
                import java.util.Arrays;
                import java.util.List;
                public class Foo {
                    public void bar(List<String> items) {
                        Collections.sort(items);
                        Arrays.asList("a", "b");
                    }
                }
                """);
        var targets = result.functions.get(0).call_targets;
        assertTrue(targets.stream().anyMatch(t -> t.contains("Collections.sort")));
        assertTrue(targets.stream().anyMatch(t -> t.contains("Arrays.asList")));
    }

    // ── Export / receiver ────────────────────────────────────────────

    @Test
    void publicMethodIsExported() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {}
                }
                """);
        assertTrue(result.functions.get(0).is_exported);
    }

    @Test
    void privateMethodIsNotExported() throws Exception {
        var result = parse("""
                public class Foo {
                    private void bar() {}
                }
                """);
        assertFalse(result.functions.get(0).is_exported);
    }

    @Test
    void receiverIsEnclosingClassName() throws Exception {
        var result = parse("""
                public class MyService {
                    public void process() {}
                }
                """);
        assertEquals("MyService", result.functions.get(0).receiver);
    }

    @Test
    void returnsListPopulatedForNonVoid() throws Exception {
        var result = parse("""
                public class Foo {
                    public int bar() { return 1; }
                }
                """);
        assertFalse(result.functions.get(0).returns.isEmpty());
        assertEquals("int", result.functions.get(0).returns.get(0).type_name);
    }

    @Test
    void returnsListEmptyForVoid() throws Exception {
        var result = parse("""
                public class Foo {
                    public void bar() {}
                }
                """);
        assertTrue(result.functions.get(0).returns.isEmpty());
    }
}
