/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * AutoJs6 class syntax (ROADMAP P4.4): the built-in constructors create their instances from
 * new.target. Runs, in the interpreted and the compiled mode, the test262 tests of subclassing the
 * built-ins ({@code subclass-builtins} and {@code subclass/builtin-objects} of the class
 * directories) and every test under {@code built-ins} that mentions {@code newTarget}, and expects
 * every test to pass. Since P6.1 {@code test262.properties} agrees with it: none of the files it
 * runs and passes is listed there, and every listed one is skipped here or the known failure.
 *
 * <p>Tests are skipped as in {@link ClassSyntaxTest262CoreTest}: modules, async tests, P9 and T10
 * features.
 */
class BuiltinSubclassingTest262Test {

    @ParameterizedTest
    @EnumSource(ClassSyntaxTest262CoreTest.Mode.class)
    void builtins(ClassSyntaxTest262CoreTest.Mode mode) throws IOException {
        Path harness = Path.of(TestSource.resolveDirectory("test262/harness/assert.js"));
        Path tests = harness.getParent().resolve("test");
        Map<String, List<Path>> groups = new LinkedHashMap<>();
        groups.put(
                "subclass-builtins",
                list(
                        tests,
                        List.of(
                                "language/statements/class/subclass-builtins",
                                "language/expressions/class/subclass-builtins"),
                        false));
        groups.put(
                "subclass/builtin-objects",
                list(tests, List.of("language/statements/class/subclass/builtin-objects"), false));
        groups.put("built-ins newTarget", list(tests, List.of("built-ins"), true));

        Map<String, String> failures = new TreeMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Map<String, Script> harnessCache = new HashMap<>();
        StringBuilder counts = new StringBuilder();
        for (Map.Entry<String, List<Path>> group : groups.entrySet()) {
            int found = group.getValue().size();
            int run = 0;
            int failed = 0;
            for (Path file : group.getValue()) {
                String relative = tests.relativize(file).toString().replace('\\', '/');
                ClassSyntaxTest262CoreTest.Case testCase =
                        new ClassSyntaxTest262CoreTest.Case(file);
                String reason = skipReason(testCase);
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
                boolean passed = true;
                for (boolean strict : strictModes) {
                    String failure =
                            ClassSyntaxTest262CoreTest.runCase(
                                    mode, testCase, relative, strict, harness, harnessCache);
                    if (failure != null) {
                        failures.putIfAbsent(relative, (strict ? "[strict] " : "") + failure);
                        passed = false;
                    }
                }
                if (!passed) {
                    failed++;
                }
            }
            counts.append(
                    String.format(
                            "  %s: %d files, %d run, %d passed%n",
                            group.getKey(), found, run, run - failed));
        }
        System.out.printf(
                "built-in subclassing test262 (%s): %d failed, skipped %s%n%s",
                mode, failures.size(), skipped, counts);

        StringBuilder report = new StringBuilder();
        failures.forEach(
                (path, failure) -> report.append(path).append(": ").append(failure).append('\n'));
        Path out = Path.of("build", "class-syntax");
        Files.createDirectories(out);
        StringBuilder all = new StringBuilder();
        failures.forEach(
                (path, failure) -> all.append(path).append(": ").append(failure).append('\n'));
        Files.writeString(out.resolve("builtins-" + mode + ".txt"), all.toString());
        assertTrue(
                groups.values().stream().allMatch(files -> !files.isEmpty()),
                "test directories not found under " + tests);
        assertEquals("", report.toString());
    }

    /** The test files of the directories, only those mentioning newTarget if asked to. */
    private static List<Path> list(Path tests, List<String> directories, boolean newTargetOnly)
            throws IOException {
        List<Path> result = new ArrayList<>();
        for (String directory : directories) {
            try (Stream<Path> walk = Files.walk(tests.resolve(directory))) {
                result.addAll(
                        walk.filter(p -> p.toString().endsWith(".js"))
                                .filter(p -> !p.getFileName().toString().contains("_FIXTURE"))
                                .sorted()
                                .collect(Collectors.toList()));
            }
        }
        if (newTargetOnly) {
            List<Path> mentioning = new ArrayList<>();
            for (Path file : result) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("newTarget")) {
                    mentioning.add(file);
                }
            }
            return mentioning;
        }
        return result;
    }

    /** Returns why the test is not run, or {@code null}. */
    private static String skipReason(ClassSyntaxTest262CoreTest.Case testCase) {
        if (testCase.hasFlag("module")) {
            return "module";
        }
        for (String feature : testCase.features) {
            if (ClassSyntaxTest262CoreTest.UNSUPPORTED.contains(feature)) {
                return feature;
            }
        }
        return null;
    }
}
