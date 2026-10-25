/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Context.EvaluationMethod;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;

/**
 * AutoJs6 (ROADMAP P6.6, appendix C Q16): spread arguments in calls, {@code f(...a)}, {@code
 * o.m(...a)}, {@code super.m(...a)} and {@code new F(...a)}, in the interpreted and the compiled
 * mode.
 */
class SpreadCallTest {

    private static final String JOIN =
            "function f() { return Array.prototype.join.call(arguments, ','); }\n";

    @Test
    void theFourForms() {
        assertWithAllModes_ES6(
                "1,2,3|9:1,2,3|B:1,2,3!|3,true,true",
                JOIN
                        + """
                var a = [1, 2, 3];
                var o = { x: 9, m() { return this.x + ':' + f(...arguments); } };
                class A { m(...xs) { return this.tag + xs.join(); } }
                class B extends A {
                  constructor() { super(); this.tag = 'B:'; }
                  m(...xs) { return super.m(...xs) + '!'; }
                }
                function P(x, y) { this.s = x + y; this.nt = new.target === P; }
                var p = new P(...[1, 2]);
                [f(...a), o.m(...a), new B().m(...a), [p.s, p.nt, p instanceof P].join()].join('|');
                """);
    }

    @Test
    void mixedAndMultipleSpreads() {
        assertWithAllModes_ES6(
                "0,1,2,3,4,5,6,7|||x|1,2",
                JOIN
                        + """
                var a = [1, 2, 3];
                [f(0, ...a, 4, ...[5, 6], 7), f(...[]), f(...[], ...[]), f(...[], 'x', ...[]),
                 f(...[1, 2],)].join('|');
                """);
    }

    @Test
    void optionalCalls() {
        assertWithAllModes_ES6(
                "undefined,undefined,0,1,2,3,1,2",
                JOIN
                        + """
                var n = 0, u, o = null, p = { m: f };
                function count() { n++; return [1, 2]; }
                [String(u?.(...count())), String(o?.m(...count())), n, f?.(...[1, 2, 3]),
                 p?.m(...[1, 2])].join();
                """);
    }

    @Test
    void spreadsAnyIterable() {
        assertWithAllModes_ES6(
                "a,b,c|1,2|1,2,3,4|1,2|7,8|q,r",
                JOIN
                        + """
                function* g() { yield 1; yield 2; }
                var custom = {
                  [Symbol.iterator]() {
                    var i = 0;
                    return { next() { return i < 2 ? { value: 'qr'[i++], done: false } : { done: true }; } };
                  }
                };
                [f(...'abc'), f(...new Set([1, 2])), f(...new Map([[1, 2], [3, 4]])), f(...g()),
                 (function () { return f(...arguments); })(7, 8), f(...custom)].join('|');
                """);
    }

    @Test
    void arraysAreIteratedObservably() {
        assertWithAllModes_ES6(
                "x|y,y|1,2",
                JOIN
                        + """
                var arr = [1, 2];
                var values = Array.prototype[Symbol.iterator];
                var r = [];
                Array.prototype[Symbol.iterator] = function* () { yield 'x'; };
                try { r.push(f(...arr)); } finally { Array.prototype[Symbol.iterator] = values; }
                var it = Object.getPrototypeOf([][Symbol.iterator]());
                var next = it.next;
                var calls = 0;
                it.next = function () { return calls++ < 2 ? { value: 'y', done: false } : { done: true }; };
                try { r.push(f(...arr)); } finally { it.next = next; }
                r.push(f(...arr));
                r.join('|');
                """);
    }

    @Test
    void nonIterablesThrowTypeError() {
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError,TypeError",
                JOIN
                        + """
                var r = [];
                for (var v of [1, null, undefined, {}, { [Symbol.iterator]: 1 }]) {
                  try { f(...v); r.push('no error'); } catch (e) { r.push(e.constructor.name); }
                }
                r.join();
                """);
    }

    @Test
    void evaluationOrder() {
        assertWithAllModes_ES6(
                "get m,arg1,iterate,arg3,call 3",
                """
                var log = [];
                var o = {
                  get m() { log.push('get m'); return function () { log.push('call ' + arguments.length); }; }
                };
                var spread = { [Symbol.iterator]() { log.push('iterate'); return [1][Symbol.iterator](); } };
                o.m(log.push('arg1'), ...spread, log.push('arg3'));
                log.join();
                """);
        assertWithAllModes_ES6(
                "1,boom|1,2,TypeError",
                """
                var log = [];
                var bad = { [Symbol.iterator]() { return { next() { throw new Error('boom'); } }; } };
                function f() { log.push('called'); }
                try { f(log.push(1), ...bad, log.push(2)); } catch (e) { log.push(e.message); }
                var r = log.join();
                log = [];
                var x = 1;
                try { x(log.push(1), ...[log.push(2)]); } catch (e) { log.push(e.constructor.name); }
                r + '|' + log.join();
                """);
    }

    @Test
    void newKeepsNewTargetAndResult() {
        assertWithAllModes_ES6(
                "true,1|7,true|2,2020|TypeError,TypeError,TypeError",
                """
                function F(...xs) { this.nt = new.target === F; this.n = xs.length; }
                function R() { return { r: 7 }; }
                class C { constructor(a, b) { this.v = a + b; } }
                class D extends C { constructor(...r) { super(...r); this.d = new.target === D; } }
                var r = [];
                var f = new F(...[0]);
                r.push([f.nt, f.n].join());
                var d = new D(...[3, 4]);
                r.push([new R(...[1]).r, d.d].join());
                r.push([new Date(...[2020, 0, 2]).getDate(), new Date(...[2020, 1]).getFullYear()].join());
                var errors = [];
                for (var g of [() => 1, { m() {} }.m, 1]) {
                  try { new g(...[]); errors.push('no error'); } catch (e) { errors.push(e.constructor.name); }
                }
                r.push(errors.join());
                r.join('|');
                """);
    }

    @Test
    void superCallKeepsThisAndHomeObject() {
        assertWithAllModes_ES6(
                "A.m(B,1,2)|A.m(B,1,2)",
                """
                var base = { m(...xs) { return 'A.m(' + this.name + ',' + xs.join() + ')'; } };
                var obj = {
                  __proto__: base, name: 'B',
                  m(...xs) { return super.m(...xs); },
                  n(...xs) { var arrow = () => super.m(...xs); return arrow(); }
                };
                [obj.m(...[1, 2]), obj.n(1, 2)].join('|');
                """);
    }

    @Test
    void doesNotUseApply() {
        assertWithAllModes_ES6(
                "1,2,3|2|B",
                JOIN
                        + """
                var apply = Function.prototype.apply, reflectApply = Reflect.apply;
                var construct = Reflect.construct;
                Function.prototype.apply = function () { throw new Error('apply used'); };
                Reflect.apply = function () { throw new Error('Reflect.apply used'); };
                Reflect.construct = function () { throw new Error('Reflect.construct used'); };
                class A { m() { return 'A'; } }
                class B extends A { m(...a) { return super.m(...a) === 'A' ? 'B' : '?'; } }
                var r;
                try {
                  r = [f(...[1, 2, 3]), new Array(...[1, 2]).length, new B().m(...[])].join('|');
                } finally {
                  Function.prototype.apply = apply;
                  Reflect.apply = reflectApply;
                  Reflect.construct = construct;
                }
                r;
                """);
    }

    @Test
    void directEval() {
        assertWithAllModes_ES6(
                "6|undefined|3|TypeError",
                """
                function g() {
                  var local = 5;
                  var r = [eval(...['local + 1']), String(eval(...[])), eval(...['1 + 2', 'ignored'])];
                  try { new eval(...['1']); } catch (e) { r.push(e.constructor.name); }
                  return r.join('|');
                }
                g();
                """);
    }

    @Test
    void reducibleFunctions() {
        assertWithAllModes_ES6(
                "b,1,2|1,2|3,4|undefined|true",
                JOIN
                        + """
                var bound = f.bind(null, 'b');
                function s() { 'use strict'; return this; }
                function sloppy() { return this === globalThis; }
                [bound(...[1, 2]), f.call(...[null, 1, 2]), f.apply(...[null, [3, 4]]),
                 String(s(...[])), sloppy(...[])].join('|');
                """);
    }

    @Test
    void referenceCallsAsWithoutSpread() {
        assertWithAllModes_ES6(
                "0,ReferenceError|0,ReferenceError",
                """
                var calls = 0;
                function g() { calls++; }
                var r = [];
                try { g(1) = 1; } catch (e) { r.push([calls, e.constructor.name].join()); }
                try { g(...[1]) = 1; } catch (e) { r.push([calls, e.constructor.name].join()); }
                r.join('|');
                """);
    }

    @Test
    void generatorsYieldInSpreadArguments() {
        assertWithAllModes_ES6(
                "1,2,3",
                JOIN
                        + """
                function* g() { return f(yield 'a', ...(yield 'b'), yield 'c'); }
                var it = g();
                it.next();
                it.next(1);
                it.next([2]);
                it.next(3).value;
                """);
    }

    @Test
    void interpretedCalleesStayInline() {
        // A deep recursion through spread calls, and a continuation captured in a function
        // called with spread arguments: both need the interpreter to run the callee inline.
        try (Context cx = Context.enter()) {
            cx.setEvaluationMethod(EvaluationMethod.Interpreter);
            cx.setLanguageVersion(Context.VERSION_ES6);
            TopLevel scope = cx.initStandardObjects();
            Object depth =
                    cx.evaluateString(
                            scope,
                            "function r(n, ...rest) { return n === 0 ? rest.length : r(n - 1, ...rest, n); }"
                                    + " r(3000);",
                            "test.js",
                            1,
                            null);
            assertEquals(3000, ((Number) depth).intValue());

            scope.defineFunctionProperties(
                    scope,
                    new String[] {"capture"},
                    SpreadCallTest.class,
                    ScriptableObject.DONTENUM);
            Script script =
                    cx.compileString(
                            "function g(a, b) { return a + b + capture(); } g(...[1, 2]);",
                            "test.js",
                            1,
                            null);
            try {
                cx.executeScriptWithContinuations(script, scope);
                fail("expected ContinuationPending");
            } catch (ContinuationPending pending) {
                Object result = cx.resumeContinuation(pending.getContinuation(), scope, 10);
                assertEquals(13, ((Number) result).intValue());
            }
        }
    }

    public static Object capture(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        throw cx.captureContinuation();
    }
}
