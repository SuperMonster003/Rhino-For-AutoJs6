/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertEcmaErrorES6;
import static org.mozilla.javascript.testutils.Utils.assertEvaluatorExceptionES6;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax (ROADMAP P3.1, P3.2): public fields and static blocks, one group per rule,
 * each case in the interpreted and the compiled mode. The Interpreter V2 instructions are covered
 * by {@code ClassInstructionsTest} (ROADMAP appendix C Q11).
 */
class ClassFieldsTest {
    private static final String ARGUMENTS =
            "\"arguments\" is not allowed in class field initializers or static blocks";

    @Test
    void baseFieldsRunBeforeTheConstructorBody() {
        assertWithAllModes_ES6(
                "1,2,x|y|z,undefined",
                """
                var seen;
                class A {
                  x = 1;
                  y = this.x + 1;
                  constructor() { seen = this.z; this.z = 3; }
                  z;
                }
                var a = new A();
                [a.x, a.y, Object.keys(a).join('|'), String(seen)].join();
                """);
        assertWithAllModes_ES6(
                "2,2",
                """
                var n = 0;
                class A { id = ++n; }
                new A(); [new A().id, n].join();
                """);
    }

    @Test
    void derivedFieldsRunWhenSuperReturns() {
        assertWithAllModes_ES6(
                "false,true,6,z|w",
                """
                var before, after;
                class B { constructor() { this.z = 3; } }
                class C extends B {
                  w = this.z * 2;
                  constructor() {
                    var f = () => super();
                    try { this.w; } catch (e) { before = e instanceof ReferenceError; }
                    f();
                    after = Object.hasOwn(this, 'w');
                  }
                }
                var c = new C();
                [!before, after, c.w, Object.keys(c).join('|')].join();
                """);
        assertWithAllModes_ES6(
                "B,C",
                """
                var order = [];
                class B { b = order.push('B'); }
                class C extends B { c = order.push('C'); }
                new C();
                order.join();
                """);
        assertWithAllModes_ES6(
                "true,1",
                """
                class B { constructor(o) { return o; } }
                class C extends B { f = 1; }
                var o = {};
                var r = new C(o);
                [r === o, o.f].join();
                """);
    }

    @Test
    void initializerIsItsOwnFunctionScope() {
        assertWithAllModes_ES6(
                "true,undefined,p,3",
                """
                class P { p() { return 'p'; } }
                class A extends P {
                  self = this;
                  nt = (() => new.target)();
                  sup = super.p();
                  arrow = () => this.self;
                  static n = 3;
                  k = A.n;
                }
                var a = new A();
                [a.arrow() === a, String(a.nt), a.sup, a.k].join();
                """);
        assertWithAllModes_ES6(
                "undefined",
                """
                class A { x = new.target; }
                String(new A().x);
                """);
        assertEvaluatorExceptionES6(ARGUMENTS, "class A { x = arguments; }");
        assertEvaluatorExceptionES6(ARGUMENTS, "class A { x = () => arguments; }");
        assertWithAllModes_ES6(
                2,
                """
                class A { x = function() { return arguments.length; }(1, 2); }
                new A().x;
                """);
    }

    @Test
    void staticFieldsRunWithTheClassAsThis() {
        assertWithAllModes_ES6(
                "10,11,true,A",
                """
                class A {
                  static s = 10;
                  static t = this.s + 1;
                  static same = this === A;
                  static n = A.name;
                }
                [A.s, A.t, A.same, A.n].join();
                """);
        assertWithAllModes_ES6(
                "x",
                """
                class A { static name = 'x'; }
                A.name;
                """);
        assertWithAllModes_ES6(
                "TypeError",
                """
                try { class A { static x = A.y.z; } } catch (e) { e.name }
                """);
    }

    @Test
    void computedKeysAreEvaluatedOnceInSourceOrder() {
        assertWithAllModes_ES6(
                "a,m,b,s|s|2|a,b|m",
                """
                var log = [];
                function k(name) { log.push(name); return name; }
                class A {
                  [k('a')] = log.length;
                  [k('m')]() {}
                  [k('b')];
                  static [k('s')] = 2;
                }
                new A(); var x = new A();
                [log.join(), Object.keys(A).join() + '|' + A.s,
                 Object.keys(x).join(), Object.getOwnPropertyNames(A.prototype)[1]].join('|');
                """);
        assertWithAllModes_ES6(
                "1,2",
                """
                var s = Symbol('s');
                var i = 0;
                class A { [s] = ++i; [i + 1] = 2; }
                var a = new A();
                [a[s], a[1]].join();
                """);
    }

    @Test
    void fieldsAreDefinedNotAssigned() {
        assertWithAllModes_ES6(
                "false,1,true,true,true",
                """
                var called = false;
                class P { set x(v) { called = true; } }
                class A extends P { x = 1; }
                var a = new A();
                var d = Object.getOwnPropertyDescriptor(a, 'x');
                [called, d.value, d.writable, d.enumerable, d.configurable].join();
                """);
        assertWithAllModes_ES6(
                2,
                """
                class P {}
                Object.defineProperty(P.prototype, 'x', { value: 1, writable: false });
                class A extends P { x = 2; }
                new A().x;
                """);
        assertEcmaErrorES6(
                "TypeError",
                """
                class B { constructor() { return Object.freeze({}); } }
                class C extends B { x = 1; }
                new C();
                """);
        assertEcmaErrorES6(
                "TypeError: Cannot define the field x on the object",
                """
                class B { constructor() { return new Proxy({}, { defineProperty: () => false }); } }
                class C extends B { x = 1; }
                new C();
                """);
        assertEcmaErrorES6(
                "TypeError",
                """
                class B { constructor() { return Object.preventExtensions({}); } }
                class C extends B { x; }
                new C();
                """);
    }

    @Test
    void anonymousDefinitionsAreNamedAfterTheField() {
        assertWithAllModes_ES6(
                "f,a,C,z1,[s],g,s,x",
                """
                var s = Symbol('s');
                class A {
                  f = function() {};
                  a = () => {};
                  C = class {};
                  ['z' + 1] = function() {};
                  [s] = () => {};
                  g = function named() {};
                  static s = class {};
                  static x = class { static n = this.name; };
                }
                var o = new A();
                [o.f.name, o.a.name, o.C.name, o.z1.name, o[s].name, 'g', A.s.name,
                 A.x.n].join();
                """);
        assertWithAllModes_ES6(
                "named",
                """
                class A { g = function named() {}; }
                new A().g.name;
                """);
    }

    @Test
    void staticBlocksInterleaveWithStaticFields() {
        assertWithAllModes_ES6(
                "1,2,3,4,true",
                """
                var log = [];
                class A {
                  static a = log.push(1);
                  static { log.push(2); this.self = this; }
                  static b = log.push(3);
                  static { log.push(4); }
                }
                [log.join(), A.self === A].join();
                """);
        assertWithAllModes_ES6(
                "undefined,undefined,1",
                """
                var x = 'outer';
                class A {
                  static { var x = 1; this.inner = x; }
                  static { this.nt = new.target; this.y = typeof y; var y; }
                }
                [String(A.nt), typeof B, A.inner].join();
                """);
        assertWithAllModes_ES6(
                "p",
                """
                class P { static p() { return 'p'; } }
                class A extends P { static { this.r = super.p(); } }
                A.r;
                """);
        assertWithAllModes_ES6(
                "Error,undefined",
                """
                var A;
                try { A = class { static { throw new Error(); } }; } catch (e) { e.name + ',' + A; }
                """);
    }

    @Test
    void staticBlockRestrictions() {
        assertEvaluatorExceptionES6(ARGUMENTS, "class A { static { arguments; } }");
        assertEvaluatorExceptionES6("invalid return", "class A { static { return; } }");
        assertEvaluatorExceptionES6(
                "await is only valid in async functions", "class A { static { await 1; } }");
        assertWithAllModes_ES6(
                1,
                """
                class A { static { this.f = function() { return arguments.length; }; } }
                A.f(0);
                """);
    }

    @Test
    void fieldInitializersSeeTheClassScope() {
        assertWithAllModes_ES6(
                "C,inner",
                """
                var D = class C { n = C.name; static v = 'inner'; w = C.v; };
                var d = new D();
                [d.n, d.w].join();
                """);
        assertWithAllModes_ES6(
                3,
                """
                function make(n) { return class { x = n; }; }
                new (make(3))().x;
                """);
    }

    @Test
    void newTargetNeedsAFunctionOtherThanAnArrowFunction() {
        assertEvaluatorExceptionES6("Invalid use of new", "var f = () => new.target;");
        assertWithAllModes_ES6(
                "undefined,SyntaxError",
                """
                class A {
                  x = eval('() => new.target');
                  y = (0, eval)('() => new.target');
                }
                var r;
                try { new A(); } catch (e) { r = e.name; }
                class B { x = eval('() => new.target'); }
                [String(new B().x()), r].join();
                """);
    }

    @Test
    void evalInFieldInitializers() {
        assertWithAllModes_ES6(
                "1,undefined",
                """
                class A { x = 1; y = eval('this.x'); z = eval('new.target'); }
                var a = new A();
                [a.y, String(a.z)].join();
                """);
        assertWithAllModes_ES6(
                "SyntaxError,SyntaxError",
                """
                var r = [];
                class A { x = eval('arguments'); }
                try { new A(); } catch (e) { r.push(e.name); }
                try { (class { static { eval('arguments'); } }); } catch (e) { r.push(e.name); }
                r.join();
                """);
    }
}
