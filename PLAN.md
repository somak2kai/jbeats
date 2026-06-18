# jbeats Implementation Plan

## Overview

A Java CLI tool that parses `.java` files and extracts per-method AST token sequences, functional imports, and call targets — mirroring what Go's `beats/pkg/ast/parse.go` does for Go files. Compiles to a GraalVM native binary. Outputs a `-beats.json` file consumable by the Go `beats` pipeline.

## Project Structure

```
jbeats/
├── pom.xml
├── src/main/java/com/jbeats/
│   ├── Main.java          # CLI entry point, JSON writer, repo detection
│   └── Parser.java        # AST walking, token extraction, import/call analysis
├── src/main/resources/META-INF/native-image/
│   └── native-image.properties
└── Makefile
```

Three files of actual code. `Main.java` handles CLI args, git-root detection, and JSON serialization. `Parser.java` handles all AST logic. No abstraction layers, no frameworks.

## Dependencies (2 total)

| Dependency | Purpose | Size | GraalVM-safe |
|---|---|---|---|
| `com.github.javaparser:javaparser-core:3.26.4` | Java AST parsing | ~2.5MB | Yes, no reflection issues |
| `com.google.code.gson:gson:2.11.0` | JSON serialization | ~300KB | Yes, native-image compatible |

JavaParser is the industry-standard Java parser. Zero transitive dependencies. Produces a full AST without needing type resolution (which would require classpath setup — wrong for a standalone tool).

Gson avoids hand-rolled JSON bugs. Single JAR, battle-tested with GraalVM.

No other dependencies. No Spring, no Guice, no logging framework (use `System.err`).

## Token Mapping

### Reused tokens (same integer values as parse.go)

Java constructs that map 1:1 to existing Go tokens:

| Go Token (int) | Java Construct | Notes |
|---|---|---|
| `TK_IF` (0) | `if / else if` | Direct match |
| `TK_FOR` (1) | `for(init;cond;update)` | Traditional for loop |
| `TK_RANGE` (2) | `for(T x : collection)` | Enhanced for-each = range |
| `TK_SWITCH` (3) | `switch` | Direct match |
| `TK_CASE` (4) | `case` / `default` | Direct match |
| `TK_RETURN` (7) | `return` | Direct match |
| `TK_CONTINUE` (11) | `continue` | Direct match |
| `TK_BREAK` (12) | `break` | Direct match |
| `TK_CALL` (14) | `localMethod()`, `this.method()` | Plain/local call |
| `TK_FUNCLIT` (15) | Lambda `(x) -> ...` | Closest equivalent |
| `TK_ASSIGN` (16) | `=`, `+=`, etc. | Assignment |
| `TK_CALL_PKG` (17) | `Collections.sort()` | Static call on imported class |
| `TK_CALL_METHOD` (18) | `obj.method()` | Instance method call |
| `TK_COMPOSITE_LIT` (19) | `new Foo()`, `new int[]{...}` | Object/array creation |
| `TK_BINARY_OP` (20) | `a && b`, `a + b`, etc. | Binary expression |
| `TK_TYPE_ASSERT` (21) | `x instanceof T`, `(T) x` | Type check / cast |

### New Java-specific tokens (added to parse.go)

Starting at ordinal 22 (continuing the iota):

| Token | Int | Java Construct | Why new |
|---|---|---|---|
| `TK_TRY` | 22 | `try { }` | No Go equivalent |
| `TK_CATCH` | 23 | `catch(Exception e) { }` | No Go equivalent |
| `TK_THROW` | 24 | `throw new ...` | No Go equivalent |
| `TK_FINALLY` | 25 | `finally { }` | No Go equivalent |
| `TK_SYNCHRONIZED` | 26 | `synchronized(lock) { }` | No Go equivalent |
| `TK_WHILE` | 27 | `while(cond) { }` | Go only has `for`; separate token lets Go layer decide mapping |
| `TK_DO_WHILE` | 28 | `do { } while(cond)` | Same rationale as while |
| `TK_ASSERT` | 29 | `assert condition` | No Go equivalent |

### Go tokens with NO Java equivalent

These will never appear in jbeats output:

| Token | Why absent |
|---|---|
| `TK_SELECT` (5) | Go channel multiplexing — no Java equivalent |
| `TK_COMM` (6) | Go comm clause — no Java equivalent |
| `TK_GO` (8) | Goroutine spawn — Java threading is fundamentally different |
| `TK_SEND` (9) | Channel send — no Java equivalent |
| `TK_DEFER` (10) | Go defer — try-with-resources is semantically different enough to not map |
| `TK_GOTO` (13) | Java has no goto (labeled break/continue map to BREAK/CONTINUE) |

## Changes to beats (Go) — Minimal

### 1. `pkg/ast/parse.go` — add constants

```go
const (
    TK_IF = iota
    // ... existing 22 tokens unchanged ...
    TK_TYPE_ASSERT
    // Java-specific tokens (jbeats emits these)
    TK_TRY
    TK_CATCH
    TK_THROW
    TK_FINALLY
    TK_SYNCHRONIZED
    TK_WHILE
    TK_DO_WHILE
    TK_ASSERT
)
```

### 2. `pkg/ast/cluster.go` — extend tokenNames

```go
var tokenNames = []string{
    "IF", "FOR", "RANGE", "SWITCH", "CASE", "SELECT", "COMM",
    "RETURN", "GO", "SEND", "DEFER", "CONTINUE", "BREAK", "GOTO",
    "CALL", "FUNCLIT", "ASSIGN", "CALL_PKG", "CALL_METHOD",
    "COMPOSITE_LIT", "BINARY_OP", "TYPE_ASSERT",
    // Java-specific
    "TRY", "CATCH", "THROW", "FINALLY", "SYNCHRONIZED",
    "WHILE", "DO_WHILE", "ASSERT",
}
```

That's it for the Go side. No logic changes. The clustering/similarity algorithms work on arbitrary integer sequences, so Java tokens integrate automatically.

## CLI Interface

```
./jbeats --inp=<path-to-java-file> [--repo=<repo-name>]
```

- `--inp` (required): Path to a `.java` file (absolute or relative)
- `--repo` (optional): Override repo name. If omitted, walks up from file to find `.git` and uses that directory's basename.

## Output

### Path

```
$TMPDIR/beats/<repo-name>/<relative-path>/FileName-beats.json
```

Example: `--inp=/home/user/myapp/src/com/example/Service.java` with git root at `/home/user/myapp` produces:

```
/tmp/beats/myapp/src/com/example/Service-beats.json
```

Directories are created automatically.

### JSON Schema

```json
{
  "file": "src/com/example/Service.java",
  "package": "com.example",
  "imports": ["java.util.List", "java.util.Map", "org.slf4j.Logger"],
  "functions": [
    {
      "name": "processOrder",
      "package": "com.example",
      "file_name": "Service.java",
      "file_path": "src/com/example/Service.java",
      "start_line": 42,
      "end_line": 78,
      "line_count": 37,
      "is_method": true,
      "is_exported": true,
      "receiver": "Service",
      "params": [
        {"type_name": "Order", "is_func_type": false, "is_interface": false}
      ],
      "returns": [
        {"type_name": "boolean", "is_error": false}
      ],
      "token_seq": [16, 0, 14, 17, 18, 22, 23, 7],
      "token_seq_hash": [293847, 182736, ...],
      "call_targets": ["org.slf4j.Logger.info", "java.util.List.add"],
      "direct_imports": ["org.slf4j.Logger", "java.util.List"],
      "imports": ["java.util.List", "java.util.Map", "org.slf4j.Logger"],
      "generated_code": false,
      "test_code": false,
      "is_constructor": false,
      "body": "public boolean processOrder(Order order) { ... }"
    }
  ]
}
```

Field names match Go's `FunctionMeta` struct exactly (snake_case).

## Parser Logic Detail

### Call Classification (mirrors `classifyCall` in parse.go)

```
Collections.sort(list)     → TK_CALL_PKG    (static call, "Collections" is an imported class)
order.getItems()           → TK_CALL_METHOD  (instance method call)
processInternal()          → TK_CALL         (local/unqualified call)
this.validate()            → TK_CALL         (explicit this = local call)
super.process()            → TK_CALL_METHOD  (super call)
```

### Import Alias Map (mirrors `buildImportAliasMap`)

Java doesn't have import aliases. The map is simpler:

```
import java.util.Collections  → {"Collections" → "java.util.Collections"}
import java.util.List         → {"List" → "java.util.List"}
import java.util.*            → skip (cannot resolve without classpath)
import static java.lang.Math.abs → {"abs" → "java.lang.Math.abs"}
```

Wildcard imports (`import x.y.*`) are recorded in the file-level `imports` array but cannot be used for call target resolution without type resolution. This is a known limitation — see Limitations section.

### Direct Imports (mirrors `extractDirectImports`)

Walk the method body. For every `ClassName.method()` or `ClassName.FIELD` reference, check if `ClassName` is in the import alias map. If yes, add the full import path to `direct_imports`.

### Call Targets (mirrors `extractCallTargets`)

Same walk as direct imports, but records `full.import.path.MethodName` instead of just the import path.

### Test Code Detection (mirrors `isTestFile`)

```java
// Java test framework imports
"org.junit.Test"
"org.junit.jupiter.api.Test"
"org.junit.jupiter.api.Assertions"
"org.testng.annotations.Test"
"org.mockito.Mockito"
```

### Constructor Detection (mirrors `isTrivialConstructor`)

A constructor is "trivial" if:
1. Method name matches class name (it's a constructor)
2. Body has exactly one statement
3. That statement is either just field assignments from params, or a `super()` call

### Generated Code Detection (mirrors `isGeneratedCode`)

Check for comments containing `"Generated by"`, `"AUTO-GENERATED"`, `"@Generated"`, or the `@javax.annotation.Generated` / `@javax.annotation.processing.Generated` annotation.

### Structural Features / Token Walk

Walk the method body AST. For each node type:

```
IfStmt           → TK_IF, complexity++
ForStmt          → TK_FOR, complexity++
ForEachStmt      → TK_RANGE, complexity++
WhileStmt        → TK_WHILE, complexity++
DoStmt           → TK_DO_WHILE, complexity++
SwitchStmt       → TK_SWITCH
SwitchEntry      → TK_CASE (if has labels), complexity++
ReturnStmt       → TK_RETURN
ContinueStmt     → TK_CONTINUE
BreakStmt        → TK_BREAK
ThrowStmt        → TK_THROW
TryStmt          → TK_TRY
CatchClause      → TK_CATCH, complexity++
finally block    → TK_FINALLY
SynchronizedStmt → TK_SYNCHRONIZED
MethodCallExpr   → classify as TK_CALL / TK_CALL_PKG / TK_CALL_METHOD
LambdaExpr       → TK_FUNCLIT
AssignExpr        → TK_ASSIGN
VariableDecl     → TK_ASSIGN (if has initializer)
ObjectCreation   → TK_COMPOSITE_LIT
BinaryExpr       → TK_BINARY_OP (complexity++ for && / ||)
CastExpr         → TK_TYPE_ASSERT
InstanceOfExpr   → TK_TYPE_ASSERT
AssertStmt       → TK_ASSERT
```

After body walk, append `TK_RETURN` × return-count (mirrors Go's trailing return tokens).

### Token Sequence Hashing (mirrors `hash.ComputeWindowHash`)

Rabin-Karp sliding window: window=3, step=2, base=131, mod=1_000_000_007. Identical constants to Go's `pkg/hash/hash.go`.

## GraalVM Native Image Build

### Build steps

```bash
# 1. Package fat JAR
mvn clean package

# 2. Build native image
native-image \
  --no-fallback \
  --gc=serial \
  -H:+ReportExceptionStackTraces \
  -jar target/jbeats.jar \
  -o jbeats
```

### Architecture support

Target matrix (from .goreleaser.yml):

| OS | Arch | GraalVM Support | Notes |
|---|---|---|---|
| linux | amd64 | ✅ | Primary target |
| linux | arm64 | ✅ | Needs ARM64 GraalVM JDK |
| darwin | amd64 | ✅ | Intel Mac |
| darwin | arm64 | ✅ | Apple Silicon |
| windows | amd64 | ✅ | Needs Visual Studio Build Tools |

**Critical difference from Go:** GraalVM native-image does NOT support cross-compilation. Each target must be built on its own OS/arch. CI needs runners for each platform (GitHub Actions provides all five).

### JVM CLI args at runtime

GraalVM native images support these runtime flags:
- ✅ `-Xms4g`, `-Xmx8g`, `-Xss2m` (heap/stack sizing)
- ✅ `-XX:+PrintGC` (GC info)
- ❌ `-javaagent` (not supported in native image)
- ❌ `-XX:+UseG1GC` (GC chosen at build time; serial is default)
- ⚠️ `-Dproperty=value` — supported if built with `-DARGS_ARE_PASSED` or by reading them in code

To maximize runtime flag support, build with:
```
-R:MaxHeapSize=0  # allow runtime -Xmx override
```

## Limitations & Unsupported Features

### Cannot support without type resolution

1. **Wildcard imports** (`import java.util.*`): Cannot determine which classes from the package are actually used. Workaround: use explicit imports. The wildcard is still recorded in file-level `imports`.

2. **Method overload resolution**: If `Foo.bar(int)` and `Foo.bar(String)` exist, we can't distinguish which is called. Call target is recorded as `com.example.Foo.bar` (without parameter types).

3. **Chained calls**: `getService().process().result()` — only the outermost call is visible without type info. Each `.method()` emits TK_CALL_METHOD but call target resolution only works for the first receiver if it's a known import.

4. **Inherited methods**: A call to `this.toString()` won't resolve to `java.lang.Object.toString` without type hierarchy info.

5. **Inner/nested class method scoping**: Methods in inner classes are parsed as standalone methods with the inner class as receiver. No class hierarchy context.

### Language features not covered (core Java only)

- Annotations on methods: recorded but not tokenized (they're metadata, not control flow)
- Enum methods: parsed normally, enum constants with bodies are skipped
- Record classes (Java 16+): constructor/accessor methods parsed normally
- Sealed classes (Java 17+): no impact on method-level analysis
- Pattern matching in switch (Java 21+): switch/case tokens still emitted; pattern detail lost

### Not in scope

- Scala, Kotlin, Groovy (per requirements)
- Java modules (`module-info.java`) — skipped
- `package-info.java` — skipped

## Implementation Phases

### Phase 1: Core parser + JSON output
- `Parser.java`: AST walk, token extraction, import/call analysis
- `Main.java`: CLI parsing, git root detection, JSON output, tmpdir path logic
- Hashing: Rabin-Karp window hash matching Go's constants

### Phase 2: GraalVM native build
- `pom.xml` with maven-shade-plugin (fat JAR) + native-maven-plugin
- `native-image.properties` for build flags
- `Makefile` for convenience

### Phase 3: Go-side integration (minimal)
- Add 8 token constants to `parse.go`
- Extend `tokenNames` in `cluster.go`
- No other Go changes

### Phase 4: Testing
- Run against known Java files, verify JSON structure
- Compare token sequences against manual inspection
- Test on large files (10K+ lines) for OOM resilience
- Test native binary with `-Xms` / `-Xmx` flags

## File-by-file summary

| File | Lines (est.) | What it does |
|---|---|---|
| `pom.xml` | ~80 | Maven build, shade plugin, native-image plugin, 2 deps |
| `Main.java` | ~200 | CLI arg parsing, git root walk, JSON write, tmpdir path |
| `Parser.java` | ~400 | Full AST walk, all extraction logic, data classes as static inner classes |
| `Makefile` | ~30 | `build`, `native`, `clean` targets |
| `native-image.properties` | ~5 | `--no-fallback --gc=serial` |
| `parse.go` (edit) | +10 lines | 8 new token constants |
| `cluster.go` (edit) | +2 lines | 8 new token names |
