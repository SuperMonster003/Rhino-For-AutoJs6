/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Kit;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.testutils.TestSource;
import org.yaml.snakeyaml.Yaml;

/**
 * AutoJs6 class syntax (ROADMAP P2.5, P3.5): runs the two test262 class directories (everything but
 * async functions, which belong to P9) in the interpreted and the compiled mode, and expects every
 * test to pass; the counts are reported for the ES2015 core, the public fields and static blocks,
 * and the private names (ROADMAP 1.4). {@code Test262SuiteTest} keeps skipping the class features
 * until P6.1 regenerates {@code test262.properties} (ROADMAP appendix C Q9). Tests that need later
 * phases are listed in {@link #KNOWN_FAILURES}.
 *
 * <p>Like {@code Test262SuiteTest}, module and async tests are not run, nor are features Rhino does
 * not support at all (T10).
 */
class ClassSyntaxTest262CoreTest {

    private static final List<String> DIRECTORIES =
            List.of("language/statements/class", "language/expressions/class");

    /** Class features of later phases: P9 (async). */
    static final Set<String> LATER_PHASES = Set.of("async-functions", "async-iteration");

    /** The features of the public fields and static blocks (P3.1, P3.2). */
    private static final Set<String> FIELDS =
            Set.of("class-fields-public", "class-static-fields-public", "class-static-block");

    /** The features of the private names (P3.3). */
    private static final Set<String> PRIVATE_NAMES =
            Set.of(
                    "class-fields-private",
                    "class-static-fields-private",
                    "class-methods-private",
                    "class-static-methods-private",
                    "class-fields-private-in");

    /** Features outside the roadmap or not supported by Rhino at all (ROADMAP T10). */
    static final Set<String> UNSUPPORTED =
            Set.of(
                    "decorators",
                    "explicit-resource-management",
                    "iterator-helpers",
                    "Array.fromAsync",
                    "import-defer",
                    "source-phase-imports",
                    "Atomics",
                    "IsHTMLDDA",
                    "SharedArrayBuffer",
                    "tail-call-optimization",
                    "Temporal",
                    "upsert",
                    "u180e",
                    // A stage 3 proposal that changes PrivateFieldAdd; P3 follows ES2025 (ROADMAP
                    // appendix C Q14).
                    "nonextensible-applies-to-private");

    /**
     * Known failures: test path to the phase that fixes them. Each is listed in ROADMAP appendix E,
     * and the test fails once one of them passes.
     */
    private static final Map<String, String> KNOWN_FAILURES =
            Map.ofEntries(
                    p9(
                            "expressions/class/cpn-class-expr-accessors-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "expressions/class/cpn-class-expr-computed-property-name-from-async-arrow-function-expression.js"),
                    p9("expressions/class/heritage-async-arrow-function.js"),
                    p9(
                            "statements/class/cpn-class-decl-accessors-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "statements/class/cpn-class-decl-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "expressions/class/cpn-class-expr-fields-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "expressions/class/cpn-class-expr-fields-methods-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "statements/class/cpn-class-decl-fields-computed-property-name-from-async-arrow-function-expression.js"),
                    p9(
                            "statements/class/cpn-class-decl-fields-methods-computed-property-name-from-async-arrow-function-expression.js"),
                    p9("expressions/class/elements/private-async-generator-method-name.js"),
                    p9("expressions/class/elements/private-async-method-name.js"),
                    p9("expressions/class/elements/private-static-async-generator-method-name.js"),
                    p9("expressions/class/elements/private-static-async-method-name.js"),
                    p9("statements/class/elements/private-async-generator-method-name.js"),
                    p9("statements/class/elements/private-async-method-name.js"),
                    p9("statements/class/elements/private-static-async-generator-method-name.js"),
                    p9("statements/class/elements/private-static-async-method-name.js"),
                    p9("statements/class/static-init-arguments-functions.js"),
                    p9("statements/class/static-init-arguments-methods.js"));

    /** An async function or method without the async feature tags (P9). */
    private static Map.Entry<String, String> p9(String path) {
        return Map.entry("language/" + path, "P9");
    }

    enum Mode {
        INTERPRETED(Test262SuiteTest.TestMode.INTERPRETED),
        COMPILED(Test262SuiteTest.TestMode.COMPILED);

        final Test262SuiteTest.TestMode mode;

        Mode(Test262SuiteTest.TestMode mode) {
            this.mode = mode;
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void core(Mode mode) throws IOException {
        Path harness = Path.of(TestSource.resolveDirectory("test262/harness/assert.js"));
        Path tests = harness.getParent().resolve("test");
        Map<String, String> failures = new TreeMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, Script> harnessCache = new HashMap<>();
        Map<String, String> groups = new HashMap<>();
        int run = 0;
        for (String directory : DIRECTORIES) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(tests.resolve(directory))) {
                files =
                        walk.filter(p -> p.toString().endsWith(".js"))
                                .filter(p -> !p.getFileName().toString().contains("_FIXTURE"))
                                .sorted()
                                .collect(Collectors.toList());
            }
            for (Path file : files) {
                String relative = tests.relativize(file).toString().replace('\\', '/');
                Case testCase = new Case(file);
                String reason = skipReason(testCase);
                if (reason != null) {
                    skipped.merge(reason, 1, Integer::sum);
                    continue;
                }
                run++;
                groups.put(relative, group(testCase));
                List<Boolean> strictModes = new ArrayList<>();
                if (!testCase.hasFlag("onlyStrict") || testCase.hasFlag("raw")) {
                    strictModes.add(false);
                }
                if (!testCase.hasFlag("noStrict") && !testCase.hasFlag("raw")) {
                    strictModes.add(true);
                }
                for (boolean strict : strictModes) {
                    String failure =
                            runCase(mode, testCase, relative, strict, harness, harnessCache);
                    if (failure != null) {
                        failures.putIfAbsent(relative, (strict ? "[strict] " : "") + failure);
                    }
                }
            }
        }
        Set<String> unexpected = new TreeSet<>(failures.keySet());
        unexpected.removeAll(KNOWN_FAILURES.keySet());
        Set<String> fixed = new TreeSet<>(KNOWN_FAILURES.keySet());
        fixed.removeAll(failures.keySet());
        System.out.printf(
                "class test262 core (%s): %d run, %d passed, %d failed, skipped %s%n",
                mode, run, run - failures.size(), failures.size(), skipped);
        for (String group : List.of("core", "fields", "private names")) {
            long groupRun = groups.values().stream().filter(group::equals).count();
            long groupFailed =
                    failures.keySet().stream().filter(f -> group.equals(groups.get(f))).count();
            System.out.printf("  %s: %d run, %d passed%n", group, groupRun, groupRun - groupFailed);
        }
        StringBuilder report = new StringBuilder();
        for (String path : unexpected) {
            report.append(path).append(": ").append(failures.get(path)).append('\n');
        }
        // The full list, for working through failures: tests/build/class-syntax/.
        Path out = Path.of("build", "class-syntax");
        Files.createDirectories(out);
        StringBuilder all = new StringBuilder();
        failures.forEach(
                (path, failure) -> all.append(path).append(": ").append(failure).append('\n'));
        Files.writeString(out.resolve("core-" + mode + ".txt"), all.toString());
        assertTrue(run > 2000, "class directories not found under " + tests);
        assertEquals("", report.toString());
        assertEquals(Set.of(), fixed, "a known failure passes now");
    }

    /** Returns why the test is not part of the core, or {@code null}. */
    static String skipReason(Case testCase) {
        if (testCase.hasFlag("module")) {
            return "module";
        }
        if (testCase.hasFlag("async")) {
            return "async";
        }
        for (String feature : testCase.features) {
            if (UNSUPPORTED.contains(feature)) {
                return feature;
            }
        }
        for (String feature : testCase.features) {
            if (LATER_PHASES.contains(feature)) {
                return "later phase";
            }
        }
        return null;
    }

    /** The group of ROADMAP 1.4 the test counts in: private names, fields, or the core. */
    private static String group(Case testCase) {
        for (String feature : testCase.features) {
            if (PRIVATE_NAMES.contains(feature)) {
                return "private names";
            }
        }
        for (String feature : testCase.features) {
            if (FIELDS.contains(feature)) {
                return "fields";
            }
        }
        return "core";
    }

    /** Runs the test once; returns the failure, or {@code null} when it passes. */
    static String runCase(
            Mode mode,
            Case testCase,
            String name,
            boolean strict,
            Path harness,
            Map<String, Script> harnessCache) {
        try (Context cx = Test262SuiteTest.CTX_FACTORY.enterContext()) {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            cx.setGeneratingDebug(true);
            mode.mode.setup(cx);
            boolean compiled = false;
            try {
                TopLevel scope = cx.initSafeStandardObjects(new TopLevel());
                for (String file : testCase.harnessFiles) {
                    Script script =
                            harnessCache.computeIfAbsent(
                                    file, f -> compileHarness(cx, harness.resolve(f)));
                    script.exec(cx, scope, scope.getGlobalThis());
                }
                Test262SuiteTest.$262 proto = Test262SuiteTest.$262.init(cx, scope);
                Test262SuiteTest.$262.install(scope, proto);
                String source = testCase.source;
                int line = 1;
                if (strict) {
                    source = "\"use strict\";\n" + source;
                    line--;
                }
                Script script = cx.compileString(source, name, line, null);
                compiled = true;
                script.exec(cx, scope, scope.getGlobalThis());
                if (testCase.isNegative()) {
                    return "expected " + testCase.expectedError;
                }
                return null;
            } catch (RhinoException e) {
                if (!testCase.isNegative()) {
                    return e.details();
                }
                String error = errorName(e);
                if (testCase.hasEarlyError && compiled) {
                    return "expected an early " + testCase.expectedError + ", got " + error;
                }
                return testCase.expectedError.equals(error)
                        ? null
                        : "expected " + testCase.expectedError + ", got " + e.details();
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                return e.toString();
            }
        }
    }

    private static Script compileHarness(Context cx, Path file) {
        try (Reader reader = new FileReader(file.toFile())) {
            return cx.compileString(Kit.readReader(reader), file.toString(), 1, null);
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + file, e);
        }
    }

    private static String errorName(RhinoException e) {
        if (e instanceof EvaluatorException) {
            return "SyntaxError";
        }
        String details = e.details();
        int colon = details.indexOf(':');
        return colon < 0 ? details : details.substring(0, colon);
    }

    /** The metadata of a test262 test, as {@code Test262SuiteTest.Test262Case} reads it. */
    static final class Case {
        private static final Yaml YAML = new Yaml();

        final String source;
        final List<String> harnessFiles = new ArrayList<>();
        final Set<String> flags = new HashSet<>();
        final Set<String> features = new HashSet<>();
        String expectedError;
        boolean hasEarlyError;

        @SuppressWarnings("unchecked")
        Case(Path file) throws IOException {
            source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Object> metadata = new HashMap<>();
            int start = source.indexOf("/*---");
            if (start != -1) {
                metadata =
                        (Map<String, Object>)
                                YAML.load(source.substring(start + 5, source.indexOf("---*/")));
            }
            if (metadata.containsKey("negative")) {
                Map<String, String> negative = (Map<String, String>) metadata.get("negative");
                expectedError = negative.get("type");
                hasEarlyError = "early".equals(negative.get("phase"));
            }
            if (metadata.containsKey("flags")) {
                flags.addAll((Collection<String>) metadata.get("flags"));
            }
            if (metadata.containsKey("features")) {
                features.addAll((Collection<String>) metadata.get("features"));
            }
            if (!flags.contains("raw")) {
                harnessFiles.add("assert.js");
                harnessFiles.add("sta.js");
                if (metadata.containsKey("includes")) {
                    harnessFiles.addAll((List<String>) metadata.get("includes"));
                }
            }
        }

        boolean hasFlag(String flag) {
            return flags.contains(flag);
        }

        boolean isNegative() {
            return expectedError != null;
        }
    }
}
