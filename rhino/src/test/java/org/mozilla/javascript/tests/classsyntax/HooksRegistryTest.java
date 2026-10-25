/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax: {@code docs/autojs6/class-syntax/HOOKS.md} is the single source of truth
 * for the fork's hook lines (AGENTS.md 7.1 / 7.2). The marked lines in {@code rhino/src/main/java}
 * and, from P6 on, in the upstream test sources under {@code tests/src} must match the hook table
 * file by file and marker by marker, and every listed new file must exist.
 */
class HooksRegistryTest {

    private static final String[] MARKERS = {"class syntax", "async"};

    private static final String[] SOURCES = {"rhino/src/main/java", "tests/src"};

    // | `path` | class syntax | 3 | ... |
    private static final Pattern HOOK_ROW =
            Pattern.compile(
                    "^\\|\\s*`([^`]+\\.java)`\\s*\\|\\s*(class syntax|async)\\s*\\|\\s*(\\d+)\\s*\\|");

    // - `path` ...
    private static final Pattern NEW_FILE =
            Pattern.compile("^-\\s*`((?:rhino|tests)/[^`]+\\.java)`");

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("docs/autojs6/class-syntax/HOOKS.md"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("docs/autojs6/class-syntax/HOOKS.md not found");
    }

    private static List<String> section(List<String> lines, String heading) {
        List<String> result = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inside = line.startsWith("## " + heading);
                continue;
            }
            if (inside) {
                result.add(line);
            }
        }
        assertFalse(result.isEmpty(), "HOOKS.md has no section " + heading);
        return result;
    }

    @Test
    void markedLinesMatchHookTable() throws IOException {
        Path root = repositoryRoot();
        List<String> lines =
                Files.readAllLines(
                        root.resolve("docs/autojs6/class-syntax/HOOKS.md"), StandardCharsets.UTF_8);

        Map<String, Integer> documented = new TreeMap<>();
        for (String line : section(lines, "3.")) {
            Matcher m = HOOK_ROW.matcher(line);
            if (m.find()) {
                documented.merge(
                        m.group(1) + " | " + m.group(2),
                        Integer.parseInt(m.group(3)),
                        Integer::sum);
            }
        }

        Map<String, Integer> actual = new TreeMap<>();
        List<Path> files = new ArrayList<>();
        for (String sources : SOURCES) {
            try (Stream<Path> walk = Files.walk(root.resolve(sources))) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                for (String marker : MARKERS) {
                    if (line.contains("AutoJs6: " + marker)) {
                        actual.merge(relative + " | " + marker, 1, Integer::sum);
                    }
                }
            }
        }
        assertEquals(actual, documented);
    }

    @Test
    void newFilesExist() throws IOException {
        Path root = repositoryRoot();
        List<String> lines =
                Files.readAllLines(
                        root.resolve("docs/autojs6/class-syntax/HOOKS.md"), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : section(lines, "4.")) {
            Matcher m = NEW_FILE.matcher(line);
            if (m.find()) {
                count++;
                assertTrue(Files.isRegularFile(root.resolve(m.group(1))), m.group(1));
            }
        }
        assertTrue(count > 0, "HOOKS.md lists no new files");
    }
}
