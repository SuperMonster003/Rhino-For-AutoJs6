/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax (ROADMAP P2, P3, AGENTS.md section 3 item 8): the fork members of the {@link
 * Icode} chain and of the {@link Node} property ids sit at the tail of the upstream chains and do
 * not reuse a value. {@code TokenUniquenessTest} covers {@code Token} and the features.
 */
class ClassChainUniquenessTest {

    /** Fork icodes, in chain order, below the upstream icodes. */
    private static final List<String> FORK_ICODES =
            List.of(
                    "CLASS_DEFINE",
                    "CLASS_ELEMENT",
                    "CLASS_CTOR_CHECK",
                    "CLASS_THIS",
                    "CLASS_TDZ",
                    "CLASS_SUPER_FUNC",
                    "CLASS_SUPER_CALL",
                    "CLASS_ITER_REST",
                    "CLASS_REQUIRE_OBJECT",
                    "CLASS_ITER_CLOSE",
                    "CLASS_PARAM_SCOPE",
                    "CLASS_PRIVATE_NAME",
                    "CLASS_INIT_FIELDS",
                    "CLASS_STATIC_INIT",
                    "SPREAD_CALL",
                    "SPREAD_CALL_ON_SUPER",
                    "SPREAD_NEW",
                    "SPREAD_CALLSPECIAL",
                    "SPREAD_REF_CALL",
                    "CLASS_DESTRUCTURE",
                    "CLASS_BOUND_NAME",
                    "CLASS_LOGICAL_BIND",
                    "CLASS_AWAIT",
                    "CLASS_FOR_AWAIT",
                    "CLASS_OPTIONAL",
                    "CLASS_OPTIONAL_CALLEE",
                    "CLASS_BLOCK_FUNCTION");

    /** The emitters of the fork icodes, which write the operands that follow them. */
    private static final List<String> FORK_EMITTERS =
            List.of("ClassInterpreter.java", "SpreadCall.java");

    private static final Pattern EMIT =
            Pattern.compile("\\bgen\\.add(?:Icode|IndexOp|StringOp)\\(");
    private static final Pattern ICODE = Pattern.compile("\\bIcode\\.(\\w+)");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** A script that compiles to all the fork icodes, see {@link #forkIcodesDumpWithTheirSpan}. */
    private static final String ALL_FORK_ICODES =
            """
            class B {}
            class A extends B {
                #p = 1;
                x = 1;
                constructor(...r) { super(...r); super(); this.y = 1; }
                static { this.z = 1; }
                m() { return this.#p; }
            }
            class T { static [(T, 'k')]() {} }
            var [a, ...b] = [1, 2];
            var {} = {};
            for (var x of []) { break; }
            function p(a = z) { var z; }
            function r([c, ...d]) {}
            class C { x = 1; constructor() {} m() { super.m(...[]); } }
            function s(f, o, F) {
                f(...[]);
                new F(...[]);
                o.m(...[]);
                eval(...[]);
                o.f(...[]) = 1;
                f(...[]).x;
            }
            async function h() { await 1; for await (var q of []) {} }
            var o = {};
            with (o) { y ||= 1; y += 1; }
            o?.a;
            o?.();
            switch (1) { case 0: function f() {} }
            """;

    /** Fork node properties, in chain order, after the upstream properties. */
    private static final List<String> FORK_PROPS = List.of("CLASS_PROP", "DEFAULT_NAME_PROP");

    private static Map<String, Integer> intConstants(Class<?> type) throws IllegalAccessException {
        Map<String, Integer> result = new HashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == int.class) {
                field.setAccessible(true);
                result.put(field.getName(), field.getInt(null));
            }
        }
        return result;
    }

    @Test
    void icodesAreUniqueWithForkIcodesLast() throws IllegalAccessException {
        Map<String, Integer> icodes = intConstants(Icode.class);
        icodes.remove("MIN_ICODE");
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : icodes.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            assertNull(previous, "Icode." + entry.getKey() + " reuses the value of " + previous);
        }
        int first = icodes.get(FORK_ICODES.get(0));
        for (int i = 0; i < FORK_ICODES.size(); i++) {
            String name = FORK_ICODES.get(i);
            assertEquals(first - i, (int) icodes.get(name), name);
            assertEquals(name, ClassInterpreter.icodeName(first - i));
            assertTrue(Icode.validIcode(first - i), name);
        }
        assertEquals(first - FORK_ICODES.size() + 1, Icode.MIN_ICODE);
        for (Map.Entry<String, Integer> entry : icodes.entrySet()) {
            if (!FORK_ICODES.contains(entry.getKey())) {
                assertTrue(entry.getValue() > first, "Icode." + entry.getKey() + " follows fork");
            }
        }
    }

    /**
     * Appendix C Q60: the operand bytes that an emitter writes after a fork icode are the ones
     * {@link Interpreter#bytecodeSpan} steps over. A missing one makes the walk over the icode read
     * an operand as an instruction, which fails for some operand values only. The emitters can only
     * write such bytes with {@code gen.addUint8}, right after the icode.
     */
    @Test
    void operandsOfForkIcodesAreInTheirSpan() throws IOException, IllegalAccessException {
        Map<String, Set<Integer>> written = new HashMap<>();
        Path sources = repositoryRoot().resolve("rhino/src/main/java/org/mozilla/javascript");
        for (String emitter : FORK_EMITTERS) {
            String source = Files.readString(sources.resolve(emitter));
            String[] statements = COMMENT.matcher(source).replaceAll("").split(";");
            for (int i = 0; i < statements.length; i++) {
                if (!EMIT.matcher(statements[i]).find()) {
                    continue;
                }
                int bytes = 0;
                while (statements[i + 1 + bytes].strip().startsWith("gen.addUint8(")) {
                    bytes++;
                }
                Matcher icode = ICODE.matcher(statements[i]);
                while (icode.find()) {
                    written.computeIfAbsent(icode.group(1), k -> new HashSet<>()).add(bytes);
                }
            }
        }
        Map<String, Integer> icodes = intConstants(Icode.class);
        for (String name : FORK_ICODES) {
            int span = Interpreter.bytecodeSpan(icodes.get(name));
            assertEquals(Set.of(span - 1), written.get(name), name);
        }
    }

    /**
     * The dump of the interpreter checks every instruction against {@link
     * Interpreter#bytecodeSpan}. The script has to cover a new fork icode.
     */
    @Test
    void forkIcodesDumpWithTheirSpan() {
        String dump =
                InterpreterIcodeCapture.capture(
                        () -> {
                            try (Context cx = Context.enter()) {
                                cx.setLanguageVersion(Context.VERSION_ES6);
                                cx.setInterpretedMode(true);
                                cx.compileString(ALL_FORK_ICODES, "test.js", 1, null);
                            }
                        });
        List<String> missing = FORK_ICODES.stream().filter(name -> !dump.contains(name)).toList();
        assertEquals(List.of(), missing, "icodes that the script of this test does not cover");
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(dir.resolve("docs/autojs6/class-syntax/HOOKS.md"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    @Test
    void nodePropertiesAreUniqueWithForkPropertiesLast() throws IllegalAccessException {
        Map<String, Integer> props = new HashMap<>();
        intConstants(Node.class)
                .forEach(
                        (name, value) -> {
                            if (name.endsWith("_PROP")
                                    && !name.equals("FIRST_PROP")
                                    && !name.equals("LAST_PROP")) {
                                props.put(name, value);
                            }
                        });
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : props.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            assertNull(previous, "Node." + entry.getKey() + " reuses the value of " + previous);
        }
        int first = props.get(FORK_PROPS.get(0));
        for (int i = 0; i < FORK_PROPS.size(); i++) {
            assertEquals(first + i, (int) props.get(FORK_PROPS.get(i)), FORK_PROPS.get(i));
        }
        assertEquals(first + FORK_PROPS.size() - 1, Node.LAST_PROP);
        for (Map.Entry<String, Integer> entry : props.entrySet()) {
            if (!FORK_PROPS.contains(entry.getKey())) {
                assertTrue(entry.getValue() < first, "Node." + entry.getKey() + " follows fork");
            }
        }
    }
}
