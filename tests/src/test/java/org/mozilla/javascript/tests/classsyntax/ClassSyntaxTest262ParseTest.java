/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.testutils.TestSource;

/**
 * AutoJs6 class syntax (ROADMAP P1.6): every parse-phase SyntaxError test in the two test262 class
 * directories is rejected by the parser, and every other test in them parses, except the listed
 * gaps of the shared generator parsing. This runs the parser only; the full {@code
 * Test262SuiteTest} unlocks these directories in P6.1.
 *
 * <p>Tests whose features are outside the roadmap (T10: decorators and the like) and module tests
 * are skipped, as in appendix E of the roadmap.
 */
class ClassSyntaxTest262ParseTest {

    private static final List<String> DIRECTORIES =
            List.of("language/statements/class", "language/expressions/class");

    /** Features outside the roadmap (ROADMAP T10). */
    private static final Set<String> OUT_OF_SCOPE =
            Set.of(
                    "decorators",
                    "explicit-resource-management",
                    "iterator-helpers",
                    "Array.fromAsync",
                    "import-defer",
                    "source-phase-imports");

    /**
     * Positive tests that do not parse yet because of the shared generator parsing (yield in a
     * comma expression or an argument list must be parenthesized; yield inside an object spread);
     * the same tests fail for plain generator functions. To be fixed by P6 (ROADMAP appendix C).
     */
    private static final Set<String> KNOWN_PARSE_GAPS =
            Set.of(
                    "language/statements/class/async-gen-method-static/yield-spread-obj.js",
                    "language/statements/class/async-gen-method/yield-spread-obj.js",
                    "language/statements/class/cpn-class-decl-accessors-computed-property-name-from-yield-expression.js",
                    "language/statements/class/cpn-class-decl-computed-property-name-from-yield-expression.js",
                    "language/statements/class/cpn-class-decl-fields-computed-property-name-from-yield-expression.js",
                    "language/statements/class/cpn-class-decl-fields-methods-computed-property-name-from-yield-expression.js",
                    "language/statements/class/definition/methods-gen-yield-as-expression-with-rhs.js",
                    "language/statements/class/definition/methods-gen-yield-as-expression-without-rhs.js",
                    "language/statements/class/elements/async-gen-private-method-static/yield-spread-obj.js",
                    "language/statements/class/elements/async-gen-private-method/yield-spread-obj.js",
                    "language/statements/class/elements/gen-private-method-static/yield-spread-obj.js",
                    "language/statements/class/elements/gen-private-method/yield-spread-obj.js",
                    "language/statements/class/gen-method-static/yield-spread-obj.js",
                    "language/statements/class/gen-method/yield-spread-obj.js",
                    "language/expressions/class/async-gen-method-static/yield-spread-obj.js",
                    "language/expressions/class/async-gen-method/yield-spread-obj.js",
                    "language/expressions/class/cpn-class-expr-accessors-computed-property-name-from-yield-expression.js",
                    "language/expressions/class/cpn-class-expr-computed-property-name-from-yield-expression.js",
                    "language/expressions/class/cpn-class-expr-fields-computed-property-name-from-yield-expression.js",
                    "language/expressions/class/cpn-class-expr-fields-methods-computed-property-name-from-yield-expression.js",
                    "language/expressions/class/elements/async-gen-private-method-static/yield-spread-obj.js",
                    "language/expressions/class/elements/async-gen-private-method/yield-spread-obj.js",
                    "language/expressions/class/elements/gen-private-method-static/yield-spread-obj.js",
                    "language/expressions/class/elements/gen-private-method/yield-spread-obj.js",
                    "language/expressions/class/gen-method-static/yield-spread-obj.js",
                    "language/expressions/class/gen-method/yield-spread-obj.js");

    private static final Pattern FRONT_MATTER =
            Pattern.compile("/\\*---(.*?)---\\*/", Pattern.DOTALL);
    private static final Pattern PARSE_PHASE = Pattern.compile("phase:\\s*parse");
    private static final Pattern FEATURES =
            Pattern.compile("features:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern FLAGS = Pattern.compile("flags:\\s*\\[(.*?)\\]", Pattern.DOTALL);

    @Test
    void classDirectoriesParse() throws IOException {
        Path test262 =
                Path.of(TestSource.resolveDirectory("test262/harness/assert.js")).getParent();
        Path tests = test262.resolve("test");
        int negatives = 0;
        int positives = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        Set<String> stillGaps = new TreeSet<>();

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
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher frontMatter = FRONT_MATTER.matcher(source);
                String meta = frontMatter.find() ? frontMatter.group(1) : "";
                Set<String> flags = list(FLAGS, meta);
                Set<String> features = list(FEATURES, meta);
                if (flags.contains("module")
                        || features.stream().anyMatch(OUT_OF_SCOPE::contains)) {
                    skipped++;
                    continue;
                }
                boolean negative = meta.contains("negative:") && PARSE_PHASE.matcher(meta).find();
                List<Boolean> strictModes = new ArrayList<>();
                if (!flags.contains("noStrict") && !flags.contains("raw")) {
                    strictModes.add(true);
                }
                if (!flags.contains("onlyStrict")) {
                    strictModes.add(false);
                }
                boolean allRejected = true;
                String firstError = null;
                for (boolean strict : strictModes) {
                    String error = parse(strict ? "\"use strict\";\n" + source : source, relative);
                    if (error == null) {
                        allRejected = false;
                    } else if (firstError == null) {
                        firstError = error;
                    }
                }
                if (negative) {
                    negatives++;
                    if (!allRejected) {
                        failures.add("accepted: " + relative);
                    }
                } else {
                    positives++;
                    if (firstError != null && !KNOWN_PARSE_GAPS.contains(relative)) {
                        failures.add("rejected: " + relative + " (" + firstError + ")");
                    } else if (firstError != null) {
                        stillGaps.add(relative);
                    }
                }
            }
        }
        System.out.printf(
                "class test262 parse: %d negative, %d positive (%d known gaps), %d skipped%n",
                negatives, positives, stillGaps.size(), skipped);
        assertTrue(negatives > 1200, "class directories not found under " + tests);
        assertEquals(List.of(), failures);
        assertEquals(new TreeSet<>(KNOWN_PARSE_GAPS), stillGaps, "a known gap parses now");
    }

    private static Set<String> list(Pattern pattern, String meta) {
        Set<String> result = new TreeSet<>();
        Matcher m = pattern.matcher(meta);
        if (m.find()) {
            for (String item : m.group(1).split(",")) {
                if (!item.trim().isEmpty()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    /** Returns the syntax error message, or {@code null} when the source parses. */
    private static String parse(String source, String name) {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setErrorReporter(cx.getErrorReporter());
            try {
                new Parser(env).parse(source, name, 1);
                return null;
            } catch (RhinoException e) {
                return e.details();
            } catch (Parser.ParserException e) {
                return "parser exception";
            }
        }
    }
}
