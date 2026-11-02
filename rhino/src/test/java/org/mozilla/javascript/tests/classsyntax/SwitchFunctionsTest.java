/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.testutils.Utils;

/**
 * AutoJs6 (appendix C Q59): the block of a switch initializes the function declarations of all its
 * cases on entry, and Annex B.3.3 gives an ordinary function its var binding only where the
 * declaration is written, in both modes.
 */
class SwitchFunctionsTest {

    private static void assertBody(String expected, String body) {
        assertWithAllModes_ES6(expected, "String((function () { " + body + " })());");
    }

    @Test
    void functionOfACaseThatDoesNotRun() {
        assertBody("function", "switch (1) { case 0: function f() {} case 1: return typeof f; }");
        assertBody("function", "switch (1) { case 1: return typeof f; default: function f() {} }");
        assertBody(
                "2",
                """
                switch (1) {
                    case 0: function f() { return 2; }
                    case 1: return String(f());
                }
                """);
    }

    @Test
    void selectorsSeeTheFunctions() {
        assertBody(
                "hit",
                "switch (1) { case f(): return 'hit'; default: function f() { return 1; } }");
    }

    @Test
    void theExpressionOfTheSwitchDoesNotSeeThem() {
        assertBody(
                "ReferenceError",
                """
                'use strict';
                try {
                    switch (f) { default: function f() {} }
                } catch (e) {
                    return e.name;
                }
                """);
    }

    @Test
    void theLastDuplicateWins() {
        assertBody(
                "2,2",
                """
                var inside;
                switch (0) {
                    case 0: inside = f();
                    case 1: function f() { return 1; }
                    case 2: function f() { return 2; }
                }
                return [inside, f()].join();
                """);
    }

    @Test
    void theVarBindingIsAssignedWhereTheDeclarationIsWritten() {
        assertBody(
                "undefined,function,function,function",
                """
                var read = () => typeof f;
                var r = [];
                switch (1) {
                    case 1: r.push(read(), typeof f);
                    case 2: function f() {}
                    case 3: r.push(read());
                }
                r.push(read());
                return r.join();
                """);
    }

    @Test
    void aDeclarationThatIsNotReachedLeavesTheVarBindingAlone() {
        assertBody(
                "undefined",
                """
                switch (1) { case 1: break; case 2: function f() {} }
                return typeof f;
                """);
    }

    @Test
    void bothBindingsHoldTheSameFunction() {
        assertBody(
                "true",
                """
                var inner;
                switch (1) { case 1: function f() {} inner = f; }
                return inner === f;
                """);
    }

    @Test
    void theCopyReadsTheBlockBindingAtThatTime() {
        assertBody("1", "switch (1) { case 1: f = 1; case 2: function f() {} } return f;");
        assertBody("function", "switch (1) { case 1: function f() {} f = 1; } return typeof f;");
    }

    @Test
    void strictModeOnlyHasTheBlockBinding() {
        assertBody(
                "function,undefined",
                """
                'use strict';
                var inside;
                switch (1) { case 0: function f() {} case 1: inside = typeof f; }
                return [inside, typeof f].join();
                """);
    }

    @Test
    void aConflictingLetKeepsTheBlockBindingOnly() {
        assertBody(
                "1",
                """
                let f = 1;
                { switch (1) { case 1: function f() {} } }
                return f;
                """);
    }

    @Test
    void generatorsOnlyHaveTheBlockBinding() {
        assertWithAllModes_ES6(
                "function,undefined",
                """
                (function () {
                    var inside;
                    switch (1) { case 0: function* g() {} case 1: inside = typeof g; }
                    return [inside, typeof g].join();
                })();
                """);
    }

    @Test
    void generatorBody() {
        assertWithAllModes_ES6(
                "function,1,undefined",
                """
                function* g() {
                    switch (1) {
                        case 0: function f() {}
                        case 1: yield typeof f; yield 1;
                    }
                    yield typeof f;
                }
                var i = g();
                [i.next().value, i.next().value, i.next().value].join();
                """);
    }

    @Test
    void asyncFunctions() {
        assertLog(
                "function|undefined|function|undefined",
                """
                async function a() {
                    switch (1) {
                        case 0: async function f() {}
                        case 1: log.push(typeof f);
                    }
                    log.push(typeof f);
                }
                async function b() {
                    switch (1) {
                        case 0: async function* f() {}
                        case 1: log.push(typeof f);
                    }
                    log.push(typeof f);
                }
                a();
                b();
                """);
    }

    @Test
    void aClosureSeesTheLexicalBindingsOfTheBlock() {
        assertBody(
                "1",
                """
                switch (1) {
                    case 0: function f() { return x; }
                    case 1: let x = 1; return f();
                }
                """);
    }

    @Test
    void everyEntryCreatesNewFunctions() {
        assertBody(
                "false",
                """
                var a = [];
                for (var i = 0; i < 2; i++) {
                    switch (i) { case 5: function f() {} default: a.push(f); }
                }
                return a[0] === a[1];
                """);
    }

    @Test
    void scriptAndEvalCode() {
        assertWithAllModes_ES6(
                "function",
                "var r; switch (1) { case 0: function f() {} case 1: r = typeof f; } r;");
        assertWithAllModes_ES6("undefined", "switch (1) { case 0: function g() {} } typeof g;");
        assertWithAllModes_ES6(
                "function,undefined,function",
                """
                (function () {
                    var r = [];
                    eval("switch (1) { case 0: function f() {} case 1: r.push(typeof f); }");
                    r.push(typeof f);
                    eval("switch (1) { case 1: function h() {} }");
                    r.push(typeof h);
                    return r.join();
                })();
                """);
    }

    @Test
    void labelsAndBreaksStillWork() {
        assertBody(
                "function,undefined",
                """
                var r = [];
                out: switch (1) {
                    case 1:
                        r.push(typeof f);
                        break out;
                    case 2:
                        function f() {}
                }
                r.push(typeof f);
                return r.join();
                """);
    }

    @Test
    void beforeES6TheDeclarationRunsWhereItIsWritten() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_1_8);
                    Object value =
                            cx.evaluateString(
                                    cx.initStandardObjects(),
                                    """
                                    (function () {
                                        switch (1) {
                                            case 0: function f() {}
                                            case 1: return typeof f;
                                        }
                                    })();
                                    """,
                                    "test.js",
                                    1,
                                    null);
                    assertEquals("undefined", ScriptRuntime.toString(value));
                    return null;
                });
    }
}
