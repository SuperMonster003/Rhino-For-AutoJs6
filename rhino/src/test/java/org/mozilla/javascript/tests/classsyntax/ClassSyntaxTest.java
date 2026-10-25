/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertEcmaErrorES6;
import static org.mozilla.javascript.testutils.Utils.assertEvaluatorExceptionES6;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax (ROADMAP P2.5): the ES2015 class semantics, one group per rule, each case in
 * the interpreted and the compiled mode. Interpreter V2 has no compiler from the IR yet; its class
 * instructions are covered by {@code ClassInstructionsTest} (ROADMAP appendix C Q11).
 */
class ClassSyntaxTest {

    @Test
    void declarationBindsLikeLet() {
        assertWithAllModes_ES6(
                "function,undefined",
                """
                class A {}
                var r = typeof A;
                { class B {} }
                r + ',' + typeof B;
                """);
        assertWithAllModes_ES6(1, "class A {} A = 1; A;");
    }

    @Test
    void innerNameIsConstant() {
        assertWithAllModes_ES6(
                "TypeError,A",
                """
                var C = class A { m() { try { A = 1; } catch (e) { return e.name; } } };
                var D = C;
                C = null;
                new D().m() + ',' + D.name;
                """);
        assertWithAllModes_ES6(
                "ReferenceError",
                """
                try { class A extends A {} } catch (e) { e.name }
                """);
        assertWithAllModes_ES6(
                "ReferenceError",
                """
                try { class A { [A]() {} } } catch (e) { e.name }
                """);
    }

    @Test
    void methodsAreNonEnumerableAndStrict() {
        assertWithAllModes_ES6(
                "0;0;constructor|m|g|s;true",
                """
                class A {
                  m() { return this; }
                  get g() { return 1; }
                  set s(v) {}
                  static t() {}
                }
                [Object.keys(A.prototype).length, Object.keys(A).length,
                 Object.getOwnPropertyNames(A.prototype).join('|'),
                 A.prototype.m.call(undefined) === undefined].join(';');
                """);
        assertWithAllModes_ES6(
                "false,true,true",
                """
                class A { m() {} }
                var d = Object.getOwnPropertyDescriptor(A.prototype, 'm');
                [d.enumerable, d.writable, d.configurable].join();
                """);
        assertEcmaErrorES6("TypeError", "class A { m() {} } new A.prototype.m();");
    }

    @Test
    void computedKeysEvaluateOnceInOrder() {
        assertWithAllModes_ES6(
                "a,b,c|1,2,3",
                """
                var log = [];
                function k(n, v) { log.push(n); return v; }
                class A {
                  [k('a', 'x')]() { return 1; }
                  static [k('b', 'y')]() { return 2; }
                  get [k('c', 'z')]() { return 3; }
                }
                log.join() + '|' + [new A().x(), A.y(), new A().z].join();
                """);
        assertWithAllModes_ES6(
                "iter",
                """
                class A { [Symbol.iterator]() { return 'iter'; } }
                new A()[Symbol.iterator]();
                """);
    }

    @Test
    void accessorsAndStaticMembers() {
        assertWithAllModes_ES6(
                "5,10,s",
                """
                class A {
                  constructor() { this._v = 5; }
                  get v() { return this._v; }
                  set v(x) { this._v = x * 2; }
                  static get s() { return 's'; }
                }
                var a = new A();
                var r = [a.v];
                a.v = 5;
                r.push(a.v, A.s);
                r.join();
                """);
    }

    @Test
    void generatorMethods() {
        assertWithAllModes_ES6(
                "1,2,3",
                """
                class A {
                  *g() { yield 1; yield* [2, 3]; }
                  static *[Symbol.iterator]() {}
                }
                [...new A().g()].join();
                """);
    }

    @Test
    void superPropertyAccess() {
        assertWithAllModes_ES6(
                "A.m,B.m;A.s,B.s;7",
                """
                class A { m() { return 'A.m'; } static s() { return 'A.s'; } get p() { return 7; } }
                class B extends A {
                  m() { return super.m() + ',B.m'; }
                  static s() { return super.s() + ',B.s'; }
                  get p() { return super.p; }
                }
                [new B().m(), B.s(), new B().p].join(';');
                """);
        assertWithAllModes_ES6(
                "x",
                """
                class A { m() { return 'x'; } }
                class B extends A { m() { var f = () => super.m(); return f(); } }
                new B().m();
                """);
    }

    @Test
    void defaultConstructors() {
        assertWithAllModes_ES6(
                "0,1,2,3",
                """
                class A { constructor(...a) { this.a = a.length; } }
                class B extends A {}
                class C {}
                [new C().constructor === C ? 0 : -1, new B(1).a, new B(1, 2).a, new B(1, 2, 3).a]
                    .join();
                """);
        assertWithAllModes_ES6(
                "0,0",
                """
                class A {}
                class B extends A {}
                [A.length, B.length].join();
                """);
    }

    @Test
    void classConstructorsNeedNew() {
        assertEcmaErrorES6(
                "TypeError: Class constructor A cannot be invoked without \"new\"",
                "class A {} A();");
        assertEcmaErrorES6(
                "TypeError: Class constructor A cannot be invoked without \"new\"",
                "class A {} A.call({});");
        assertWithAllModes_ES6(
                true,
                """
                class A {}
                Reflect.construct(A, []) instanceof A;
                """);
    }

    @Test
    void derivedConstructorThis() {
        assertEcmaErrorES6(
                "ReferenceError: Must call the super constructor",
                "class A {} class B extends A { constructor() { this.x = 1; } } new B();");
        assertEcmaErrorES6(
                "ReferenceError: Must call the super constructor",
                "class A {} class B extends A { constructor() {} } new B();");
        assertEcmaErrorES6(
                "ReferenceError: The super constructor may only be called once",
                "class A {} class B extends A { constructor() { super(); super(); } } new B();");
        assertWithAllModes_ES6(
                "1,true",
                """
                class A { constructor() { this.a = 1; } }
                class B extends A {
                  constructor() { var f = () => super(); f(); this.ok = this.a === 1; }
                }
                var b = new B();
                [b.a, b.ok].join();
                """);
        assertWithAllModes_ES6(
                "true",
                """
                class A {}
                class B extends A {
                  constructor() { var r = eval('this') === undefined; super(); return { r: r }; }
                }
                String(new B().r);
                """);
    }

    @Test
    void derivedConstructorReturn() {
        assertWithAllModes_ES6(
                "o,true",
                """
                class A {}
                class B extends A { constructor() { return { v: 'o' }; } }
                class C extends A { constructor() { super(); return undefined; } }
                [new B().v, new C() instanceof C].join();
                """);
        assertEcmaErrorES6(
                "TypeError: Derived constructors may only return an object or undefined",
                "class A {} class B extends A { constructor() { super(); return 1; } } new B();");
        assertEcmaErrorES6(
                "TypeError: Derived constructors may only return an object or undefined",
                "class A {} class B extends A { constructor() { return null; } } new B();");
        assertWithAllModes_ES6(
                true,
                """
                class A { constructor() { return 1; } }
                new A() instanceof A;
                """);
    }

    @Test
    void newTarget() {
        assertWithAllModes_ES6(
                "true,true,true,true",
                """
                class A { constructor() { this.t = new.target; this.f = () => new.target; } }
                class B extends A {}
                function F() {}
                var o = Reflect.construct(A, [], F);
                [new A().t === A, new B().t === B, new B().f() === B, o.t === F].join();
                """);
        assertWithAllModes_ES6(
                true,
                """
                class A { m() { return new.target; } }
                new A().m() === undefined;
                """);
    }

    @Test
    void heritageIsValidated() {
        assertWithAllModes_ES6(
                "true,true",
                """
                class A extends null {}
                [Object.getPrototypeOf(A.prototype) === null,
                 Object.getPrototypeOf(A) === Function.prototype].join();
                """);
        assertEcmaErrorES6("TypeError", "class A extends null {} new A();");
        assertEcmaErrorES6("TypeError", "class A extends 1 {}");
        assertEcmaErrorES6("TypeError", "class A extends Math.max {}");
        assertEcmaErrorES6("TypeError", "function F() {} F.prototype = 3; class A extends F {}");
        assertWithAllModes_ES6(
                "1,true,true",
                """
                function F() { this.f = 1; }
                class A extends F {}
                var a = new A();
                [a.f, a instanceof F, Object.getPrototypeOf(A) === F].join();
                """);
    }

    @Test
    void builtinsCreateFromNewTarget() {
        // ROADMAP T9: script functions, Object and Function in P2; the rest of the built-ins in P4.
        assertWithAllModes_ES6(
                "true,object,true,7",
                """
                class O extends Object {}
                class F extends Function {}
                var o = new O(1);
                var f = new F('return 7');
                [Object.getPrototypeOf(o) === O.prototype, typeof o,
                 Object.getPrototypeOf(f) === F.prototype, f()].join();
                """);
        assertWithAllModes_ES6(
                "true,true",
                """
                function P() {}
                [Object.getPrototypeOf(Reflect.construct(P, [], Array)) === Array.prototype,
                 Object.getPrototypeOf(Reflect.construct(Object, [1], Array)) === Array.prototype]
                    .join();
                """);
    }

    @Test
    void namesLengthsAndPrototype() {
        assertWithAllModes_ES6(
                "A,2,function,false,false,true",
                """
                class A { constructor(a, b) {} }
                var d = Object.getOwnPropertyDescriptor(A, 'prototype');
                [A.name, A.length, typeof A, d.writable, d.enumerable,
                 A.prototype.constructor === A].join();
                """);
        assertWithAllModes_ES6(
                "C,E,,n,m",
                """
                var C = class {};
                var D = class E {};
                var o = { m: class {} };
                [C.name, D.name, (class {}).name,
                 (class { static name() { return 'n'; } }).name(), o.m.name].join();
                """);
        assertWithAllModes_ES6(
                "m,get g,set g,s",
                """
                class A { m() {} get g() {} set g(v) {} static s() {} }
                var d = Object.getOwnPropertyDescriptor(A.prototype, 'g');
                [A.prototype.m.name, d.get.name, d.set.name, A.s.name].join();
                """);
    }

    @Test
    void classCodeRestrictsCallerAndArguments() {
        // Other functions keep Rhino's behavior, strict ones included (ArgumentsTest).
        assertWithAllModes_ES6(
                "true,true,true,true,false,undefined,null",
                """
                function thrown(f) { try { f(); return false; } catch (e) { return e instanceof TypeError; } }
                class A { m() {} }
                var H = Object.getPrototypeOf(class extends function () {} {});
                function sloppy() {}
                function strict() { 'use strict'; }
                [thrown(() => A.caller), thrown(() => { A.prototype.m.arguments = 1; }),
                 thrown(() => H.arguments), A.hasOwnProperty('caller') === false,
                 thrown(() => strict.arguments), String(sloppy.caller), String(strict.arguments)].join();
                """);
    }

    @Test
    void toStringReturnsTheSource() {
        // ROADMAP appendix C Q1 = A.
        assertWithAllModes_ES6(
                "class A extends Object { m() { return 1; } }",
                "class A extends Object { m() { return 1; } } A.toString();");
        assertWithAllModes_ES6(
                "m() { return 1; }", "class A { m() { return 1; } } A.prototype.m.toString();");
    }

    @Test
    void earlyErrors() {
        assertEvaluatorExceptionES6("Invalid assignment left-hand side", "(class {}) = 1;");
        assertEvaluatorExceptionES6("Invalid increment operand", "(class {})++;");
        assertEvaluatorExceptionES6(
                "super() cannot be used with new",
                "class A extends Object { constructor() { new super(); } }");
        assertEvaluatorExceptionES6(
                "super() is only valid in the constructor of a derived class",
                "class A { m() { new super(); } }");
    }

    @Test
    void parameterScopes() {
        assertWithAllModes_ES6(
                "outside,inside",
                """
                var x = 'outside';
                var probeParams, probeBody;
                class C {
                  m(_ = probeParams = function() { return x; }) {
                    var x = 'inside';
                    probeBody = function() { return x; };
                  }
                }
                new C().m();
                [probeParams(), probeBody()].join();
                """);
        assertEcmaErrorES6("ReferenceError", "class C { m(a = b, b) {} } new C().m();");
    }

    @Test
    void destructuringParameters() {
        assertWithAllModes_ES6(
                "1,2,3|x,y|true",
                """
                class C {
                  m([a, ...b], {c: {d}, ...e}) { return [a, b.join(), d].join() + '|' + Object.keys(e); }
                  static n({f = function() {}} = {}) { return f.name === 'f'; }
                }
                new C().m([1, 2], {c: {d: 3}, x: 1, y: 2}) + '|' + C.n();
                """);
        assertWithAllModes_ES6(
                "closed",
                """
                var state = 'open';
                var it = {
                  [Symbol.iterator]() {
                    return { next() { return { done: false }; }, return() { state = 'closed'; return {}; } };
                  }
                };
                class C { m([a]) {} }
                new C().m(it);
                state;
                """);
    }

    @Test
    void forOfClosesTheIterator() {
        assertWithAllModes_ES6(
                "1:closed",
                """
                var log = [];
                var it = {
                  [Symbol.iterator]() {
                    var i = 0;
                    return {
                      next() { return { value: ++i, done: false }; },
                      return() { log.push('closed'); return {}; }
                    };
                  }
                };
                class C { static m() { for (var v of it) { return v; } } }
                C.m() + ':' + log.join();
                """);
    }

    @Test
    void yieldInsideExpressions() {
        // ROADMAP appendix C Q10: compiled generators keep typed stack entries across a yield.
        assertWithAllModes_ES6(
                "a,b|{\"x\":42,\"y\":1,\"w\":2}|v|p,q",
                """
                function* g1() { var r = []; r.push(yield 1, yield 2); return r.join(); }
                function* g2() { return {...yield, y: 1, ...yield yield}; }
                function* g3() { var o = { m(x) { return x; } }; return o.m(yield 1); }
                function* g4() { return [yield 1, yield 2]; }
                function run(g, values) {
                  var it = g();
                  var r = it.next();
                  for (var v of values) r = it.next(v);
                  return r.value;
                }
                [run(g1, ['a', 'b']),
                 JSON.stringify(run(g2, [{x: 42}, {z: 1}, {w: 2}])),
                 run(g3, ['v']),
                 run(g4, ['p', 'q']).join()].join('|');
                """);
        assertWithAllModes_ES6(
                "9,8,9,8",
                """
                function* g() {
                  class C { [yield 9]() { return 9; } static [yield 8]() { return 8; } }
                  return C;
                }
                var it = g();
                var a = it.next().value;
                var b = it.next('m').value;
                var C = it.next('s').value;
                [a, b, new C().m(), C.s()].join();
                """);
        assertWithAllModes_ES6(
                1,
                """
                function f(x) { return x; }
                function* g() { return f(yield 1); }
                g().next().value;
                """);
    }
}
