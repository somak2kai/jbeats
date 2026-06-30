package com.jbeats;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a Java source file and extracts per-method metadata:
 * token sequences, functional imports (direct_imports), and call targets.
 */
public class Parser {

    // Use BLEEDING_EDGE so modern keywords (yield, record, sealed, permits, text
    // blocks) are recognised. Clear ALL processors: the validators call
    // PropertyMetaModel.getValue() via Field.get() reflection which GraalVM
    // native-image cannot resolve. CommentsInserter is also removed —
    // generated-code
    // detection therefore scans the raw source string instead of getAllComments().
    static {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        config.getProcessors().clear();
        StaticJavaParser.setConfiguration(config);
    }

    // ── Token constants ─────────────────────────────────────────────
    public static final int TK_IF = 0;
    public static final int TK_FOR = 1;
    public static final int TK_RANGE = 2; // enhanced for-each
    public static final int TK_SWITCH = 3;
    public static final int TK_CASE = 4;
    public static final int TK_RETURN = 7;
    public static final int TK_CONTINUE = 11;
    public static final int TK_BREAK = 12;
    public static final int TK_CALL = 14;
    public static final int TK_FUNCLIT = 15; // lambda
    public static final int TK_ASSIGN = 16;
    public static final int TK_CALL_PKG = 17; // static call on imported class
    public static final int TK_CALL_METHOD = 18; // instance method call
    public static final int TK_COMPOSITE_LIT = 19; // new Foo(), new int[]{...}
    public static final int TK_BINARY_OP = 20;
    public static final int TK_TYPE_ASSERT = 21; // instanceof, cast
    public static final int TK_TRY = 22;
    public static final int TK_CATCH = 23;
    public static final int TK_THROW = 24;
    public static final int TK_FINALLY = 25;
    public static final int TK_SYNCHRONIZED = 26;
    public static final int TK_WHILE = 27;
    public static final int TK_DO_WHILE = 28;
    public static final int TK_ASSERT = 29;

    // Test framework import prefixes — covers both regular and static imports.
    // Static imports return the full member path (e.g.
    // org.junit.Assert.assertTrue),
    // so we match by prefix rather than exact string.
    private static final List<String> TEST_IMPORT_PREFIXES = List.of(
            "org.junit", // JUnit 4 + 5 (org.junit.Test, org.junit.Assert.*, etc.)
            "org.testng", // TestNG
            "org.mockito", // Mockito
            "org.powermock", // PowerMock
            "io.cucumber", // Cucumber (modern)
            "cucumber.api", // Cucumber (legacy))
            "cucumber.runtime" // Cucumber runtime
    );

    public static class ParamInfo {
        public String type_name;
        public boolean is_func_type;
        public boolean is_interface;

        public ParamInfo(String typeName, boolean isFuncType, boolean isInterface) {
            this.type_name = typeName;
            this.is_func_type = isFuncType;
            this.is_interface = isInterface;
        }
    }

    public static class ReturnInfo {
        public String type_name;
        public boolean is_error;

        public ReturnInfo(String typeName, boolean isError) {
            this.type_name = typeName;
            this.is_error = isError;
        }
    }

    public static class FunctionMeta {
        public String name;
        public String package_name;
        public String file_name;
        public String file_path;
        public int start_line;
        public int end_line;
        public int line_count;
        public boolean is_method;
        public boolean is_exported;
        public String receiver;
        public List<ParamInfo> params;
        public List<ReturnInfo> returns;
        public List<Integer> token_seq;
        public List<String> call_targets;
        public List<String> direct_imports;
        public boolean generated_code;
        public boolean test_code;
        public boolean is_constructor;
        public String body;
    }

    public static class FileResult {
        public List<FunctionMeta> functions;
        public boolean test_code;
    }

    public static FileResult parseFile(Path filePath) throws IOException {
        String pathStr = filePath.toString();
        String fileName = filePath.getFileName().toString();
        if (pathStr.contains("src/main/resources")
                || pathStr.contains("src/test/resources")
                || pathStr.contains("asciidoc")
                || fileName.endsWith("package-info.java")
                || fileName.endsWith("module-info.java")) {
            FileResult r = new FileResult();
            FunctionMeta meta = new FunctionMeta();
            meta.generated_code = true;
            r.functions = List.of(meta);
            return r;
        }
        // Test sources — return early with test_code=true without parsing
        if (pathStr.contains("src/test/")) {
            FileResult r = new FileResult();
            FunctionMeta meta = new FunctionMeta();
            meta.test_code = true;
            r.functions = List.of(meta);
            return r;
        }

        String source = Files.readString(filePath);
        CompilationUnit cu = StaticJavaParser.parse(source);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        List<String> fileImports = extractImports(cu);
        Map<String, String> aliasMap = buildImportAliasMap(cu);
        boolean isGenerated = isGeneratedCode(cu, source);
        boolean isTest = isTestFile(fileImports);

        String filePathStr = filePath.toString();
        List<FunctionMeta> functions = new ArrayList<>();
        cu.findAll(MethodDeclaration.class).forEach(md -> {
            FunctionMeta fm = processMethod(md, packageName, filePath.getFileName().toString(), filePathStr, aliasMap);
            if (fm != null) {
                fm.generated_code = isGenerated;
                fm.test_code = isTest;
                functions.add(fm);
            }
        });

        cu.findAll(ConstructorDeclaration.class).forEach(cd -> {
            FunctionMeta fm = processConstructor(cd, packageName, filePath.getFileName().toString(), filePathStr,
                    aliasMap);
            if (fm != null) {
                fm.generated_code = isGenerated;
                fm.test_code = isTest;
                functions.add(fm);
            }
        });

        FileResult result = new FileResult();
        result.functions = functions;
        return result;
    }

    private static List<String> extractImports(CompilationUnit cu) {
        List<String> out = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            out.add(imp.getNameAsString());
        }
        return out;
    }

    /**
     * Maps simple class name -> full import path.
     * Skips wildcard imports (cannot resolve without classpath).
     */
    private static Map<String, String> buildImportAliasMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk())
                continue;

            String fullPath = imp.getNameAsString();
            if (imp.isStatic()) {
                int lastDot = fullPath.lastIndexOf('.');
                if (lastDot > 0) {
                    String memberName = fullPath.substring(lastDot + 1);
                    map.put(memberName, fullPath);
                }
            } else {
                int lastDot = fullPath.lastIndexOf('.');
                if (lastDot > 0) {
                    String simpleName = fullPath.substring(lastDot + 1);
                    map.put(simpleName, fullPath);
                }
            }
        }
        return map;
    }

    private static FunctionMeta processMethod(MethodDeclaration md,
            String packageName,
            String fileName,
            String filePath,
            Map<String, String> aliasMap) {

        if (!md.getBody().isPresent())
            return null; // abstract method

        FunctionMeta fm = new FunctionMeta();
        fm.name = md.getNameAsString();
        fm.package_name = packageName;
        fm.file_name = fileName;
        fm.file_path = filePath;

        md.getBegin().ifPresent(pos -> fm.start_line = pos.line);
        md.getEnd().ifPresent(pos -> fm.end_line = pos.line);
        fm.line_count = fm.end_line - fm.start_line + 1;

        fm.is_method = true;
        fm.is_exported = md.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
        fm.receiver = md.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString).orElse("");

        fm.params = extractParams(md.getParameters());
        fm.returns = extractReturns(md.getTypeAsString());

        List<Integer> tokens = new ArrayList<>();
        int[] complexity = { 1 };
        walkBody(md.getBody().get(), tokens, complexity, aliasMap);

        // Append TK_RETURN per return count (mirrors parse.go)
        for (int i = 0; i < fm.returns.size(); i++) {
            tokens.add(TK_RETURN);
        }

        fm.token_seq = tokens;
        fm.call_targets = extractCallTargets(md.getBody().get(), aliasMap);
        fm.direct_imports = extractDirectImports(md.getBody().get(), aliasMap);
        fm.is_constructor = false;
        fm.body = md.toString();
        return fm;
    }

    private static FunctionMeta processConstructor(ConstructorDeclaration cd,
            String packageName,
            String fileName,
            String filePath,
            Map<String, String> aliasMap) {

        FunctionMeta fm = new FunctionMeta();
        fm.name = cd.getNameAsString();
        fm.package_name = packageName;
        fm.file_name = fileName;
        fm.file_path = filePath;

        cd.getBegin().ifPresent(pos -> fm.start_line = pos.line);
        cd.getEnd().ifPresent(pos -> fm.end_line = pos.line);
        fm.line_count = fm.end_line - fm.start_line + 1;

        fm.is_method = true;
        fm.is_exported = cd.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
        fm.receiver = cd.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString).orElse("");

        fm.params = extractParams(cd.getParameters());
        fm.returns = Collections.emptyList();

        List<Integer> tokens = new ArrayList<>();
        int[] complexity = { 1 };
        walkBody(cd.getBody(), tokens, complexity, aliasMap);

        fm.token_seq = tokens;
        fm.call_targets = extractCallTargets(cd.getBody(), aliasMap);
        fm.direct_imports = extractDirectImports(cd.getBody(), aliasMap);
        fm.is_constructor = isTrivialConstructor(cd);
        fm.body = cd.toString();
        return fm;
    }

    private static List<ParamInfo> extractParams(List<Parameter> params) {
        List<ParamInfo> out = new ArrayList<>();
        for (Parameter p : params) {
            String typeName = p.getTypeAsString();
            boolean isFuncType = typeName.contains("Function") || typeName.contains("Consumer")
                    || typeName.contains("Supplier") || typeName.contains("Predicate")
                    || typeName.contains("Runnable") || typeName.contains("Callable");
            boolean isInterface = false;
            out.add(new ParamInfo(typeName, isFuncType, isInterface));
        }
        return out;
    }

    private static List<ReturnInfo> extractReturns(String returnType) {
        if (returnType.equals("void")) {
            return Collections.emptyList();
        }
        boolean isError = returnType.equals("Exception") || returnType.equals("Throwable")
                || returnType.endsWith("Exception");
        return List.of(new ReturnInfo(returnType, isError));
    }

    /**
     * Walks the method body and appends token IDs to the list.
     * Mirrors extractStructuralFeatures in parse.go.
     */
    private static void walkBody(Node body, List<Integer> tokens, int[] complexity,
            Map<String, String> aliasMap) {
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            switch (node.getClass().getSimpleName()) {
                case "IfStmt":
                    tokens.add(TK_IF);
                    complexity[0]++;
                    break;
                case "ForStmt":
                    tokens.add(TK_FOR);
                    complexity[0]++;
                    break;
                case "ForEachStmt":
                    tokens.add(TK_RANGE);
                    complexity[0]++;
                    break;
                case "WhileStmt":
                    tokens.add(TK_WHILE);
                    complexity[0]++;
                    break;
                case "DoStmt":
                    tokens.add(TK_DO_WHILE);
                    complexity[0]++;
                    break;
                case "SwitchStmt":
                    tokens.add(TK_SWITCH);
                    break;
                case "SwitchEntry":
                    if (!((SwitchEntry) node).getLabels().isEmpty()) {
                        complexity[0]++;
                    }
                    tokens.add(TK_CASE);
                    break;
                case "ReturnStmt":
                    tokens.add(TK_RETURN);
                    break;
                case "ContinueStmt":
                    tokens.add(TK_CONTINUE);
                    break;
                case "BreakStmt":
                    tokens.add(TK_BREAK);
                    break;
                case "ThrowStmt":
                    tokens.add(TK_THROW);
                    break;
                case "TryStmt":
                    tokens.add(TK_TRY);
                    if (((TryStmt) node).getFinallyBlock().isPresent()) {
                        tokens.add(TK_FINALLY);
                    }
                    break;
                case "CatchClause":
                    tokens.add(TK_CATCH);
                    complexity[0]++;
                    break;
                case "SynchronizedStmt":
                    tokens.add(TK_SYNCHRONIZED);
                    break;
                case "AssertStmt":
                    tokens.add(TK_ASSERT);
                    break;
                case "MethodCallExpr":
                    tokens.add(classifyCall((MethodCallExpr) node, aliasMap));
                    break;
                case "LambdaExpr":
                    tokens.add(TK_FUNCLIT);
                    break;
                case "AssignExpr":
                    tokens.add(TK_ASSIGN);
                    break;
                case "VariableDeclarationExpr":
                    tokens.add(TK_ASSIGN);
                    break;
                case "ObjectCreationExpr":
                    tokens.add(TK_COMPOSITE_LIT);
                    break;
                case "ArrayCreationExpr":
                    tokens.add(TK_COMPOSITE_LIT);
                    break;
                case "BinaryExpr":
                    BinaryExpr be = (BinaryExpr) node;
                    if (be.getOperator() == BinaryExpr.Operator.AND
                            || be.getOperator() == BinaryExpr.Operator.OR) {
                        complexity[0]++;
                    }
                    tokens.add(TK_BINARY_OP);
                    break;
                case "CastExpr":
                    tokens.add(TK_TYPE_ASSERT);
                    break;
                case "InstanceOfExpr":
                    tokens.add(TK_TYPE_ASSERT);
                    break;
                default:
                    break;
            }
        });
    }

    /**
     * Classifies a method call expression.
     * - TK_CALL_PKG: static call on an imported class (Collections.sort)
     * - TK_CALL_METHOD: instance method call (obj.method())
     * - TK_CALL: unqualified local call (helper())
     */
    private static int classifyCall(MethodCallExpr call, Map<String, String> aliasMap) {
        if (!call.getScope().isPresent()) {
            return TK_CALL;
        }

        Expression scope = call.getScope().get();

        if (scope instanceof ThisExpr) {
            return TK_CALL;
        }
        if (scope instanceof SuperExpr) {
            return TK_CALL_METHOD;
        }

        if (scope instanceof NameExpr) {
            String name = ((NameExpr) scope).getNameAsString();
            if (aliasMap.containsKey(name)) {
                return TK_CALL_PKG;
            }
            if (Character.isUpperCase(name.charAt(0))) {
                return TK_CALL_PKG;
            }
            return TK_CALL_METHOD;
        }

        return TK_CALL_METHOD;
    }

    private static List<String> extractCallTargets(Node body, Map<String, String> aliasMap) {
        Set<String> seen = new LinkedHashSet<>();
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            if (!(node instanceof MethodCallExpr))
                return;
            MethodCallExpr call = (MethodCallExpr) node;
            if (!call.getScope().isPresent())
                return;

            Expression scope = call.getScope().get();
            if (!(scope instanceof NameExpr))
                return;

            String name = ((NameExpr) scope).getNameAsString();
            String importPath = aliasMap.get(name);
            if (importPath != null) {
                seen.add(importPath + "." + call.getNameAsString());
            }
        });
        return new ArrayList<>(seen);
    }

    private static List<String> extractDirectImports(Node body, Map<String, String> aliasMap) {
        Set<String> seen = new LinkedHashSet<>();
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            if (!(node instanceof NameExpr))
                return;
            String name = ((NameExpr) node).getNameAsString();
            String importPath = aliasMap.get(name);
            if (importPath != null) {
                seen.add(importPath);
            }
        });
        return new ArrayList<>(seen);
    }

    private static boolean isTestFile(List<String> imports) {
        for (String imp : imports) {
            for (String prefix : TEST_IMPORT_PREFIXES) {
                if (imp.startsWith(prefix))
                    return true;
            }
        }
        return false;
    }

    private static boolean isGeneratedCode(CompilationUnit cu, String source) {
        // Scan raw source for generated-code comment markers.
        int limit = 0, lines = 0;
        for (; limit < source.length() && lines < 200; limit++) {
            if (source.charAt(limit) == '\n')
                lines++;
        }
        String header = source.substring(0, limit).toUpperCase();
        if (header.contains("GENERATED BY") || header.contains("AUTO-GENERATED")
                || header.contains("CODE GENERATED") || header.contains("DO NOT EDIT")
                || header.contains("DO NOT MODIFY")) {
            return true;
        }
        // @Generated annotations are AST nodes — not affected by CommentsInserter.
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td.getAnnotationByName("Generated").isPresent()
                    || td.getAnnotationByName("javax.annotation.Generated").isPresent()
                    || td.getAnnotationByName("javax.annotation.processing.Generated").isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrivialConstructor(ConstructorDeclaration cd) {
        BlockStmt bodyBlock = cd.getBody();
        List<Statement> stmts = bodyBlock.getStatements();
        if (stmts.isEmpty())
            return true;

        for (Statement stmt : stmts) {
            if (stmt instanceof ExplicitConstructorInvocationStmt) {
                continue;
            }
            if (stmt instanceof ExpressionStmt) {
                Expression expr = ((ExpressionStmt) stmt).getExpression();
                if (expr instanceof AssignExpr) {
                    AssignExpr assign = (AssignExpr) expr;
                    if (assign.getTarget() instanceof FieldAccessExpr) {
                        continue;
                    }
                }
            }
            return false;
        }
        return true;
    }
}
