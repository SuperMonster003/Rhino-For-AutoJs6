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
 * AutoJs6 (appendix C Q61): a block initializes its function declarations on entry, also the
 * labelled ones, and Annex B.3.3 gives an ordinary function its var binding only where the
 * declaration is written, in both modes. The switch has the same rules, see SwitchFunctionsTest.
 */
class BlockFunctionsTest {

    private static void assertBody(String expected, String body) {
        assertWithAllModes_ES6(expected, "String((function () { " + body + " })());");
    }

    private static void assertLegacy(String expected, String script) {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_1_8);
                    Object value =
                            cx.evaluateString(cx.initStandardObjects(), script, "test.js", 1, null);
                    assertEquals(expected, ScriptRuntime.toString(value));
                    return null;
                });
    }

    @Test
    void theVarBindingIsAssignedWhereTheDeclarationIsWritten() {
        assertBody(
                "undefined,function,function,function",
                """
                var read = () => typeof f;
                var r = [];
                {
                    r.push(read(), typeof f);
                    function f() {}
                    r.push(read());
                }
                r.push(read());
                return r.join();
                """);
    }

    @Test
    void theCopyReadsTheBlockBindingAtThatTime() {
        assertBody("1", "{ f = 1; function f() {} } return f;");
        assertBody("function", "{ function f() {} f = 1; } return typeof f;");
    }

    @Test
    void anAssignmentAfterTheDeclarationStaysInTheBlock() {
        assertBody(
                "number,function",
                """
                var inside;
                { function f() {} f = 1; inside = typeof f; }
                return [inside, typeof f].join();
                """);
    }

    @Test
    void aDeclarationThatIsNotReachedLeavesTheVarBindingAlone() {
        assertBody("undefined", "if (false) { function f() {} } return typeof f;");
        assertBody(
                "undefined", "try { { throw 1; function f() {} } } catch (e) {} return typeof f;");
        assertBody(
                "function,undefined",
                """
                var r = [];
                out: { r.push(typeof f); break out; function f() {} }
                r.push(typeof f);
                return r.join();
                """);
    }

    @Test
    void bothBindingsHoldTheSameFunction() {
        assertBody("true", "var inner; { function f() {} inner = f; } return inner === f;");
    }

    @Test
    void theDeclarationsRunInOrder() {
        assertBody(
                "2,2",
                """
                var inside;
                {
                    inside = f();
                    function f() { return 1; }
                    function f() { return 2; }
                }
                return [inside, f()].join();
                """);
        assertBody(
                "5",
                """
                {
                    function f() { return 1; }
                    f = 5;
                    function f() { return 2; }
                }
                return f;
                """);
    }

    @Test
    void nestedBlocks() {
        assertBody(
                "undefined,undefined,function,function",
                """
                var read = () => typeof f;
                var r = [read()];
                {
                    r.push(read());
                    {
                        function f() {}
                        r.push(read());
                    }
                    r.push(read());
                }
                return r.join();
                """);
        assertBody(
                "1",
                """
                {
                    function f() { return 1; }
                    { function f() { return 2; } }
                }
                return f();
                """);
    }

    @Test
    void closuresBeforeAndAfterTheDeclaration() {
        assertBody(
                "undefined,function,2,2",
                """
                var before = () => typeof f;
                var r = [];
                {
                    r.push(before());
                    var inside = () => typeof f;
                    r.push(inside());
                    function f() { return 2; }
                    var after = () => f();
                    r.push(after());
                }
                r.push(f());
                return r.join();
                """);
    }

    @Test
    void aFunctionSeesTheLexicalBindingsOfTheBlock() {
        assertBody("1", "var r; { function f() { return x; } let x = 1; r = f(); } return r;");
    }

    @Test
    void strictModeOnlyHasTheBlockBinding() {
        assertBody(
                "function,undefined",
                """
                'use strict';
                var inside;
                { inside = typeof f; function f() {} }
                return [inside, typeof f].join();
                """);
        assertBody(
                "ReferenceError",
                """
                'use strict';
                { function f() {} }
                try {
                    f;
                } catch (e) {
                    return e.name;
                }
                """);
    }

    @Test
    void aConflictingBindingKeepsTheBlockBindingOnly() {
        assertBody("1", "let f = 1; { function f() {} } return f;");
        assertBody("1", "const f = 1; { function f() {} } return f;");
        assertBody("1", "let f = 1; { { function f() {} } } return f;");
        assertBody("1", "{ let f = 1; { function f() {} } return f; }");
        assertWithAllModes_ES6(
                "1,function",
                """
                (function (f) {
                    var inside;
                    { function f() {} inside = typeof f; }
                    return [f, inside].join();
                })(1);
                """);
    }

    @Test
    void anExistingVarIsAssignedWhereTheDeclarationIsWritten() {
        assertBody(
                "1,function,function",
                """
                var f = 1;
                var r = [];
                var read = () => typeof f === 'number' ? f : typeof f;
                {
                    r.push(read());
                    function f() {}
                    r.push(read());
                }
                r.push(read());
                return r.join();
                """);
    }

    @Test
    void labelledFunctionsInABlock() {
        assertBody(
                "function,undefined,function",
                """
                var read = () => typeof f;
                var r = [];
                {
                    r.push(typeof f, read());
                    l: function f() {}
                    r.push(read());
                }
                return r.join();
                """);
        assertBody("1", "{ f = 1; l: function f() {} } return f;");
        assertBody("2", "var r; { r = f(); a: b: function f() { return 2; } } return r;");
        assertBody(
                "function,undefined",
                """
                var r = [];
                { { r.push(typeof f); l: function f() {} } }
                return r.concat(typeof g).join();
                """);
    }

    @Test
    void labelledFunctionsInASwitch() {
        assertBody(
                "function,undefined",
                """
                var r = [];
                switch (1) {
                    case 0: l: function f() {}
                    case 1: r.push(typeof f);
                }
                r.push(typeof f);
                return r.join();
                """);
        assertBody(
                "function,function",
                """
                var r = [];
                switch (1) {
                    case 1: r.push(typeof f);
                    case 2: a: b: function f() {}
                }
                r.push(typeof f);
                return r.join();
                """);
    }

    @Test
    void labelledFunctionsAtTheTopLevel() {
        assertBody("function", "var r = typeof f; l: function f() {} return r;");
        assertBody("1", "f = 1; l: function f() {} return f;");
        assertWithAllModes_ES6("function", "var r = typeof f; l: function f() {} r;");
        assertWithAllModes_ES6("function", "var r = typeof f; a: b: function f() {} r;");
    }

    @Test
    void anIfStatementIsNotALabelledFunction() {
        assertBody("undefined", "l: if (false) function f() {} return typeof f;");
        assertBody(
                "undefined,function",
                """
                var r = typeof f;
                l: if (true) function f() {}
                return [r, typeof f].join();
                """);
    }

    @Test
    void generatorsOnlyHaveTheBlockBinding() {
        assertWithAllModes_ES6(
                "function,undefined",
                """
                (function () {
                    var inside;
                    { inside = typeof g; function* g() {} }
                    return [inside, typeof g].join();
                })();
                """);
    }

    @Test
    void asyncFunctions() {
        assertLog(
                "function|undefined|function|undefined",
                """
                async function a() {
                    { log.push(typeof f); async function f() {} }
                    log.push(typeof f);
                }
                async function b() {
                    { log.push(typeof f); async function* f() {} }
                    log.push(typeof f);
                }
                a();
                b();
                """);
    }

    @Test
    void generatorBody() {
        assertWithAllModes_ES6(
                "function,undefined,function",
                """
                function* g() {
                    var read = () => typeof f;
                    {
                        yield typeof f;
                        yield read();
                        function f() {}
                    }
                    yield read();
                }
                var i = g();
                [i.next().value, i.next().value, i.next().value].join();
                """);
    }

    @Test
    void asyncBody() {
        assertLog(
                "undefined|function",
                """
                async function a() {
                    var read = () => typeof f;
                    {
                        await null;
                        log.push(read());
                        function f() {}
                    }
                    log.push(read());
                }
                a();
                """);
    }

    @Test
    void everyEntryCreatesNewFunctions() {
        assertBody(
                "false,true",
                """
                var a = [];
                for (var i = 0; i < 2; i++) {
                    { function f() {} a.push(f); }
                }
                return [a[0] === a[1], a[1] === f].join();
                """);
    }

    @Test
    void bodiesOfStatements() {
        assertBody(
                "undefined,function",
                """
                var r = typeof f;
                if (true) { function f() {} }
                return [r, typeof f].join();
                """);
        assertBody("function", "for (var i = 0; i < 1; i++) { function f() {} } return typeof f;");
        assertBody("function", "try { function f() {} } catch (e) {} return typeof f;");
        assertBody("function", "try { throw 1; } catch (e) { function f() {} } return typeof f;");
        assertBody("function", "try {} finally { function f() {} } return typeof f;");
    }

    @Test
    void aCatchParameterIsSkipped() {
        assertBody(
                "number,function",
                """
                var inside;
                try {
                    throw 1;
                } catch (f) {
                    inside = typeof f;
                    { function f() {} }
                }
                return [inside, typeof f].join();
                """);
    }

    @Test
    void aWithObjectIsSkipped() {
        assertBody(
                "1,function",
                """
                var o = { f: 1 };
                with (o) {
                    { function f() {} }
                }
                return [o.f, typeof f].join();
                """);
    }

    @Test
    void classBodies() {
        assertWithAllModes_ES6(
                "function,undefined",
                """
                class A {
                    static m() {
                        var r;
                        { r = typeof f; function f() {} }
                        return [r, typeof f].join();
                    }
                }
                A.m();
                """);
        assertWithAllModes_ES6(
                "function",
                """
                var r;
                class A {
                    static {
                        { r = typeof f; function f() {} }
                    }
                }
                r;
                """);
    }

    @Test
    void scriptAndEvalCode() {
        assertWithAllModes_ES6("1", "{ f = 1; function f() {} } String(f);");
        assertWithAllModes_ES6(
                "undefined,function,function",
                """
                var read = function () { return typeof f; };
                var r = [read()];
                {
                    r.push(typeof f);
                    function f() {}
                    r.push(read());
                }
                r.join();
                """);
        assertWithAllModes_ES6(
                "undefined,function,function",
                """
                (function () {
                    var read = () => typeof f;
                    var r = [];
                    eval("{ r.push(read(), typeof f); function f() {} r.push(read()); }");
                    return r.join();
                })();
                """);
        assertWithAllModes_ES6(
                "function",
                """
                (function () {
                    { let x; eval("{ function f() {} }"); }
                    return typeof f;
                })();
                """);
    }

    @Test
    void theCompletionValueIsNotChanged() {
        assertWithAllModes_ES6("1", "String(eval('1; { function f() {} }'));");
        assertWithAllModes_ES6("2", "String(eval('{ 2; function f() {} }'));");
        assertWithAllModes_ES6("3", "String(eval('{ function f() {} 3; }'));");
        assertWithAllModes_ES6("4", "String(eval('4; { l: function f() {} }'));");
    }

    @Test
    void beforeES6TheVarBindingIsAssignedOnEntryToTheBlock() {
        assertLegacy(
                "function",
                """
                (function () {
                    var read = function () { return typeof f; };
                    var r;
                    { r = read(); function f() {} }
                    return r;
                })();
                """);
    }

    @Test
    void beforeES6ALabelledFunctionRunsWhereItIsWritten() {
        assertLegacy(
                "undefined",
                """
                (function () {
                    var r;
                    { r = typeof f; l: function f() {} }
                    return r;
                })();
                """);
        assertLegacy("undefined", "var r = typeof f; l: function f() {} r;");
    }
}
