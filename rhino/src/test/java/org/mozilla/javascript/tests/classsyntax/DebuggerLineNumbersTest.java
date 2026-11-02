/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;

/**
 * AutoJs6 (appendix C Q60): a debugger asks a compiled script for its lines when the compilation is
 * done, as {@code Dim.SourceInfo} does, which walks over the icode. The walk failed on the operand
 * of the class, destructuring and {@code for await} icodes.
 */
class DebuggerLineNumbersTest {

    private static void collect(DebuggableScript script, Set<Integer> lines) {
        for (int line : script.getLineNumbers()) {
            lines.add(line);
        }
        for (int i = 0; i < script.getFunctionCount(); i++) {
            collect(script.getFunction(i), lines);
        }
    }

    private static Set<Integer> lineNumbers(String source) {
        Set<Integer> lines = new TreeSet<>();
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            cx.setGeneratingDebug(true);
            cx.setDebugger(
                    new Debugger() {
                        @Override
                        public DebugFrame getFrame(Context c, DebuggableScript s) {
                            return null;
                        }

                        @Override
                        public void handleCompilationDone(
                                Context c, DebuggableScript s, String text) {
                            if (s.isTopLevel()) {
                                collect(s, lines);
                            }
                        }
                    },
                    null);
            cx.compileString(source, "test.js", 1, null);
        }
        return lines;
    }

    private static void assertLines(String source, Integer... expected) {
        Set<Integer> lines = lineNumbers(source);
        assertTrue(lines.containsAll(Set.of(expected)), lines + " in\n" + source);
    }

    @Test
    void arrayPattern() {
        assertLines(
                """
                var a = 0;
                var [b] = [1];
                [a] = [2];
                """,
                1,
                2,
                3);
    }

    @Test
    void objectPattern() {
        assertLines(
                """
                var { p } = { p: 1 };
                ({ p } = { p: 2 });
                """,
                1,
                2);
    }

    @Test
    void forOf() {
        assertLines(
                """
                var sum = 0;
                for (var x of [1]) {
                    sum += x;
                }
                """,
                1,
                2,
                3);
        assertLines(
                """
                for (var [x] of []) {
                    x;
                }
                """,
                1,
                2);
    }

    @Test
    void spreadCall() {
        assertLines(
                """
                function f() {
                    return 1;
                }
                f(...[]);
                """,
                2,
                4);
    }

    @Test
    void defaultParameter() {
        assertLines(
                """
                function f(a = 1) {
                    return a;
                }
                """,
                2);
    }

    @Test
    void temporalDeadZone() {
        assertLines(
                """
                {
                    x;
                    let x = 1;
                }
                """,
                2,
                3);
    }

    @Test
    void classes() {
        assertLines(
                """
                class A {}
                class B extends A {
                    constructor() {
                        super();
                    }
                    m() {
                        return 1;
                    }
                }
                """,
                4,
                7);
        assertLines(
                """
                class C {
                    #p = 1;
                    static [(0, 'k')]() {
                        return 2;
                    }
                }
                """,
                4);
    }

    @Test
    void optionalChain() {
        assertLines(
                """
                var o = {};
                o?.a.b;
                o?.m();
                o?.[1];
                delete o?.a;
                """,
                1,
                2,
                3,
                4,
                5);
    }

    @Test
    void logicalAssignment() {
        assertLines(
                """
                var o = {};
                with (o) {
                    y ||= 1;
                }
                """,
                1,
                3);
    }

    @Test
    void asyncFunction() {
        assertLines(
                """
                async function f() {
                    await 1;
                    return 2;
                }
                """,
                2,
                3);
    }

    @Test
    void forAwait() {
        assertLines(
                """
                async function f() {
                    for await (var x of []) {
                        x;
                    }
                }
                """,
                2,
                3);
    }

    @Test
    void generator() {
        assertLines(
                """
                function* g() {
                    yield 1;
                    yield [2, yield 3];
                }
                """,
                2,
                3);
    }

    @Test
    void functionOfASwitch() {
        assertLines(
                """
                switch (1) {
                    case 0:
                        function f() {}
                    case 1:
                        f();
                }
                """,
                1,
                5);
    }

    @Test
    void scriptsRunUnderADebugger() {
        Set<Integer> lines = new TreeSet<>();
        Debugger debugger =
                new Debugger() {
                    @Override
                    public DebugFrame getFrame(Context c, DebuggableScript s) {
                        return new DebugFrame() {
                            @Override
                            public void onLineChange(Context cx, int lineNumber) {
                                lines.add(lineNumber);
                            }
                        };
                    }

                    @Override
                    public void handleCompilationDone(Context c, DebuggableScript s, String text) {
                        collect(s, new TreeSet<>());
                    }
                };
        String source =
                """
                var log = [];
                class A { m() { return 1; } }
                class B extends A {
                    m() {
                        return super.m() + 1;
                    }
                }
                for (var x of [new B()]) {
                    log.push(x.m());
                }
                var [y] = [3];
                log.push(y);
                async function f() {
                    for await (var v of [4]) {
                        log.push(await v);
                    }
                }
                f();
                log;
                """;
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            cx.setGeneratingDebug(true);
            cx.setDebugger(debugger, null);
            TopLevel scope = cx.initStandardObjects();
            Script script = cx.compileString(source, "test.js", 1, null);
            Object log = script.exec(cx, scope, scope);
            cx.processMicrotasks();
            assertEquals("2,3,4", ScriptRuntime.toString(log));
        }
        assertTrue(lines.containsAll(Set.of(1, 5, 8, 9, 11, 14, 15)), lines.toString());
    }
}
