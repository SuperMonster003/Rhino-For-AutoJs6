/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.testutils.TestSource;

/**
 * AutoJs6 async (ROADMAP P9.4): runs the test262 range of async in the interpreted and the compiled
 * mode, and expects every test to pass. The range is that of ROADMAP 1.4: the directories of async
 * functions, async generators, await and {@code for await}, the built-in prototypes, and the async
 * methods of classes and object literals. Only the features of T10 are skipped, as in {@link
 * ClassSyntaxTest262CoreTest}.
 */
class AsyncTest262Test {

    private static final List<String> DIRECTORIES =
            List.of(
                    "language/statements/async-function",
                    "language/expressions/async-function",
                    "language/expressions/async-arrow-function",
                    "language/expressions/await",
                    "language/statements/for-await-of",
                    "language/statements/async-generator",
                    "language/expressions/async-generator",
                    "built-ins/AsyncFunction",
                    "built-ins/AsyncGeneratorFunction",
                    "built-ins/AsyncGeneratorPrototype",
                    "built-ins/AsyncFromSyncIteratorPrototype",
                    "built-ins/AsyncIteratorPrototype");

    /** The tests of these directories that count are the async ones. */
    private static final List<String> MIXED_DIRECTORIES =
            List.of(
                    "language/statements/class",
                    "language/expressions/class",
                    "language/expressions/object/method-definition");

    @ParameterizedTest
    @EnumSource(ClassSyntaxTest262CoreTest.Mode.class)
    void range(ClassSyntaxTest262CoreTest.Mode mode) throws IOException {
        Path harness = Path.of(TestSource.resolveDirectory("test262/harness/assert.js"));
        Path tests = harness.getParent().resolve("test");
        Map<String, String> failures = new TreeMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, Script> harnessCache = new HashMap<>();
        int run = 0;
        for (Path file : files(tests)) {
            String relative = tests.relativize(file).toString().replace('\\', '/');
            ClassSyntaxTest262CoreTest.Case testCase = new ClassSyntaxTest262CoreTest.Case(file);
            boolean mixed = MIXED_DIRECTORIES.stream().anyMatch(relative::startsWith);
            if (mixed && !isAsync(testCase)) {
                continue;
            }
            String reason = ClassSyntaxTest262CoreTest.skipReason(testCase);
            if (reason != null) {
                skipped.merge(reason, 1, Integer::sum);
                continue;
            }
            run++;
            List<Boolean> strictModes = new ArrayList<>();
            if (!testCase.hasFlag("onlyStrict") || testCase.hasFlag("raw")) {
                strictModes.add(false);
            }
            if (!testCase.hasFlag("noStrict") && !testCase.hasFlag("raw")) {
                strictModes.add(true);
            }
            for (boolean strict : strictModes) {
                String failure =
                        ClassSyntaxTest262CoreTest.runCase(
                                mode, testCase, relative, strict, harness, harnessCache);
                if (failure != null) {
                    failures.putIfAbsent(relative, (strict ? "[strict] " : "") + failure);
                }
            }
        }
        System.out.printf(
                "async test262 range (%s): %d run, %d passed, %d failed, skipped %s%n",
                mode, run, run - failures.size(), failures.size(), skipped);
        StringBuilder report = new StringBuilder();
        failures.forEach(
                (path, failure) -> report.append(path).append(": ").append(failure).append('\n'));
        assertTrue(run > 3500, "async directories not found under " + tests);
        assertEquals("", report.toString());
    }

    private static boolean isAsync(ClassSyntaxTest262CoreTest.Case testCase) {
        return testCase.hasFlag("async")
                || testCase.features.contains("async-functions")
                || testCase.features.contains("async-iteration");
    }

    private static List<Path> files(Path tests) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String directory :
                Stream.concat(DIRECTORIES.stream(), MIXED_DIRECTORIES.stream())
                        .collect(Collectors.toList())) {
            try (Stream<Path> walk = Files.walk(tests.resolve(directory))) {
                walk.filter(p -> p.toString().endsWith(".js"))
                        .filter(p -> !p.getFileName().toString().contains("_FIXTURE"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }
}
