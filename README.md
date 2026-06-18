# jbeats

[![CI](https://github.com/somak2kai/jbeats/actions/workflows/ci.yml/badge.svg)](https://github.com/somak2kai/jbeats/actions/workflows/ci.yml)
[![Coverage](https://codecov.io/gh/somak2kai/jbeats/branch/main/graph/badge.svg)](https://codecov.io/gh/somak2kai/jbeats)
[![Java](https://img.shields.io/badge/Java-17-blue?logo=openjdk)](https://openjdk.org/projects/jdk/17/)
[![GraalVM](https://img.shields.io/badge/GraalVM-21-orange)](https://www.graalvm.org/)

Java port of [beats](https://github.com/somak2kai/beats) — parses Java source files and emits per-method structural metadata as JSON. 

## What it does

For each method and constructor in a `.java` file, jbeats extracts:

- **Token sequence** — structural control-flow tokens (if, for, try/catch, lambda, etc.) encoded as integers
- **Call targets** — fully-qualified names of imported classes called by the method (e.g. `java.util.Collections.sort`)
- **Direct imports** — imports actually referenced in the method body
- **Metadata** — name, receiver class, line range, parameters, return type, visibility, and flags for generated/test/trivial-constructor code

Output is written as pretty-printed JSON to `$TMPDIR/beats/<repo>/<relative-path>-beats.json`.

## Build

Requires GraalVM JDK 21 with `native-image`.

```sh
# Fat JAR only
mvn clean package -DskipTests

# Fat JAR + native binary (outputs to target/jbeats)
mvn -Pnative package -DskipTests

# Run tests
mvn test
```

## Usage

```sh
./target/jbeats --inp=/path/to/MyClass.java --repo=myrepo

```

The path to the output file is printed to stdout on success. Errors go to stderr.

## Output format

```json
{
  "functions": [
    {
      "name": "processHints",
      "package_name": "org.apache.cassandra.hints",
      "file_name": "HintsDescriptor.java",
      "file_path": "src/java/org/apache/cassandra/hints/HintsDescriptor.java",
      "start_line": 42,
      "end_line": 67,
      "line_count": 26,
      "is_method": true,
      "is_exported": true,
      "receiver": "HintsDescriptor",
      "params": [
        { "type_name": "List<Hint>", "is_func_type": false, "is_interface": false }
      ],
      "returns": [
        { "type_name": "int", "is_error": false }
      ],
      "token_seq": [0, 14, 16, 1, 18, 7],
      "call_targets": ["java.util.Collections.sort"],
      "direct_imports": ["java.util.Collections"],
      "generated_code": false,
      "test_code": false,
      "is_constructor": false,
      "body": "public int processHints(...) { ... }"
    }
  ]
}
```

## Token reference

| Value | Constant | Construct |
|-------|----------|-----------|
| 0 | `TK_IF` | `if` |
| 1 | `TK_FOR` | `for` (C-style) |
| 2 | `TK_RANGE` | `for` (enhanced / for-each) |
| 3 | `TK_SWITCH` | `switch` |
| 4 | `TK_CASE` | `case` label |
| 7 | `TK_RETURN` | `return` |
| 11 | `TK_CONTINUE` | `continue` |
| 12 | `TK_BREAK` | `break` |
| 14 | `TK_CALL` | local / `this.method()` call |
| 15 | `TK_FUNCLIT` | lambda expression |
| 16 | `TK_ASSIGN` | assignment or variable declaration |
| 17 | `TK_CALL_PKG` | static call on imported class |
| 18 | `TK_CALL_METHOD` | instance method call |
| 19 | `TK_COMPOSITE_LIT` | `new Foo()` / `new T[]{}` |
| 20 | `TK_BINARY_OP` | binary operator |
| 21 | `TK_TYPE_ASSERT` | `instanceof` / cast |
| 22 | `TK_TRY` | `try` |
| 23 | `TK_CATCH` | `catch` |
| 24 | `TK_THROW` | `throw` |
| 25 | `TK_FINALLY` | `finally` |
| 26 | `TK_SYNCHRONIZED` | `synchronized` |
| 27 | `TK_WHILE` | `while` |
| 28 | `TK_DO_WHILE` | `do … while` |
| 29 | `TK_ASSERT` | `assert` |
