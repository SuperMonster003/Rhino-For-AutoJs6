/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (found in ROADMAP P6.6 / P6.7, fixed after it): upstream bugs of the compiled mode, run
 * in both modes. Not related to classes.
 */
class CompiledModeFixesTest {

    private static final String ERROR =
            "function error(f) { try { f(); return 'no error'; } catch (e) { return e.name; } }\n";

    @Test
    void nestedObjectLiteralsKeepTheHomeObjectOfMethods() {
        // The home object of a method was the last object literal generated before it, so a
        // nested literal before the method (an inline __proto__ value) became its home object.
        assertWithAllModes_ES6(
                "3|3|true|3|3|3|3|4",
                """
                var p = { x: 3 };
                var w1 = { __proto__: { x: 3 }, m() { return super.x; } };
                var w2 = { __proto__: p, inner: { a: 1 }, m() { return super.x; } };
                var w3 = {
                  a: { m() { return super.toString === Object.prototype.toString; } },
                  __proto__: p,
                  n() { return super.x; }
                };
                var w4 = { __proto__: p, deep: { b: { c: {} } }, m() { return super.x; },
                  arrow() { return (() => super.x)(); } };
                function big() {
                  var keep = function () { return p; };
                  return { __proto__: keep(), inner: { y: 1 }, a1: 1, a2: 2, a3: 3, a4: 4, a5: 5,
                    a6: 6, a7: 7, a8: 8, a9: 9, m() { return super.x; } };
                }
                var q = { x: 4 };
                var w5 = { __proto__: q, inner: {}, get g() { return super.x; } };
                [w1.m(), w2.m(), w3.a.m(), w3.n(), w4.m(), w4.arrow(), big().m(), w5.g].join('|');
                """);
    }

    @Test
    void numberCalleesThrowTypeError() {
        // A callee holding a number variable reached the call as a double (VerifyError), and an
        // assignment or increment of one failed the code generator.
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError",
                ERROR
                        + """
                function run() {
                  var r = [];
                  var x = 1;
                  r.push(error(() => { var y = 1; return y(2); }));
                  r.push(error(function () { var y = 1; return y(2); }));
                  r.push(error(function () { var y = 1; return (y = 2)(1); }));
                  r.push(error(function () { var y = 1; return (y++)(1); }));
                  r.push(error(function () { var y = 1; return y?.(2); }));
                  r.push(error(function () { var y = 1; return y(...[2]); }));
                  r.push(error(function () { var y = 1; return new y(2); }));
                  r.push(error(function () { var s = 0; for (var i = 0; i < 3; i++) s += i; return s(1); }));
                  return r.join();
                }
                run();
                """);
    }

    @Test
    void numberValuesAroundCallsStillWork() {
        assertWithAllModes_ES6(
                "e|8|20|3",
                """
                function run() {
                  var a = [function () { return 'e'; }];
                  var i = 0;
                  i = i + 0;
                  var k = 2;
                  k = k * 2;
                  function f() { return function (v) { return v + k; }; }
                  function g(p, q) { return p * q; }
                  var x = 3;
                  x = x + 1;
                  var n = 1;
                  n = n + 1;
                  var h = n;
                  h = function (v) { return v + n; };
                  return [a[i](), f()(k), g(x, x + 1), h(1)].join('|');
                }
                run();
                """);
    }
}
