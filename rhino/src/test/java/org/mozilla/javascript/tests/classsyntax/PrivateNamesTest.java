/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertEcmaErrorES6;
import static org.mozilla.javascript.testutils.Utils.assertEvaluatorExceptionES6;
import static org.mozilla.javascript.testutils.Utils.assertJavaScriptException_ES6;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax (ROADMAP P3.3): private fields, methods and accessors (ROADMAP T5), one
 * group per rule, each case in the interpreted and the compiled mode. The Interpreter V2
 * instructions are covered by {@code ClassInstructionsTest} (ROADMAP appendix C Q11).
 *
 * <p>A failed private element operation throws a TypeError object of the realm of the code that
 * runs it, a {@code JavaScriptException}; a base that is not an object throws an {@code EcmaError}
 * as property access does.
 */
class PrivateNamesTest {

    @Test
    void privateFieldsBelongToEachInstance() {
        assertWithAllModes_ES6(
                "1,2,true,false",
                """
                class A {
                  #x = 1;
                  get() { return this.#x; }
                  set(v) { this.#x = v; }
                  static has(o) { return #x in o; }
                }
                var a = new A(), b = new A();
                b.set(2);
                [a.get(), b.get(), A.has(a), A.has({})].join();
                """);
        assertWithAllModes_ES6(
                "undefined",
                """
                class A { #x; get() { return this.#x; } }
                String(new A().get());
                """);
    }

    @Test
    void everyClassEvaluationHasItsOwnNames() {
        assertWithAllModes_ES6(
                "1,TypeError,false",
                """
                function make() {
                  return class { #x = 1; static get(o) { return o.#x; } static has(o) { return #x in o; } };
                }
                var A = make(), B = make();
                var r = [A.get(new A())];
                try { B.get(new A()); } catch (e) { r.push(e.name); }
                r.push(B.has(new A()));
                r.join();
                """);
    }

    @Test
    void methodsAndAccessorsLiveOnTheBrand() {
        assertWithAllModes_ES6(
                "42,g,set,true",
                """
                class A {
                  #m() { return 42; }
                  get #g() { return 'g'; }
                  set #s(v) { this.log = v; }
                  m() { return this.#m; }
                  run() { this.#s = 'set'; return [this.#m(), this.#g, this.log].join(); }
                }
                var a = new A();
                [a.run(), a.m() === new A().m()].join();
                """);
        assertWithAllModes_ES6(
                "0,0",
                """
                class A { #m() {} get #g() { return 1; } }
                var a = new A();
                [Object.getOwnPropertyNames(a).length,
                 Object.getOwnPropertyNames(A.prototype).length - 1].join();
                """);
        assertWithAllModes_ES6(
                "A,#m",
                """
                class A { #m() {} static n(o) { return o.#m.name; } }
                [A.name, A.n(new A())].join();
                """);
    }

    @Test
    void methodsAndAccessorsAreReadOnlyOrOneSided() {
        assertJavaScriptException_ES6(
                "TypeError: Private method #m is not writable",
                "class A { #m() {} constructor() { this.#m = 1; } } new A();");
        assertJavaScriptException_ES6(
                "TypeError: Private accessor #g was defined without a setter",
                "class A { get #g() { return 1; } constructor() { this.#g = 1; } } new A();");
        assertJavaScriptException_ES6(
                "TypeError: Private accessor #s was defined without a getter",
                "class A { set #s(v) {} constructor() { this.#s; } } new A();");
        assertWithAllModes_ES6(
                2,
                """
                class A {
                  #v = 1;
                  get #x() { return this.#v; }
                  set #x(v) { this.#v = v; }
                  inc() { this.#x++; return this.#x; }
                }
                new A().inc();
                """);
    }

    @Test
    void staticPrivateMembersBelongToTheClass() {
        assertWithAllModes_ES6(
                "2,2,3,true",
                """
                class A {
                  static #c = 0;
                  static #twice(v) { return v * 2; }
                  static get #three() { return 3; }
                  static inc() { return ++A.#c; }
                  static run() { return [A.#twice(1), A.#three, #c in A].join(); }
                }
                A.inc();
                [A.inc(), A.run()].join();
                """);
    }

    @Test
    void brandChecksThrowTypeErrors() {
        assertJavaScriptException_ES6(
                "TypeError: Cannot read private member #x from an object whose class did not declare it",
                "class A { #x; static r(o) { return o.#x; } } A.r({});");
        assertJavaScriptException_ES6(
                "TypeError: Cannot write private member #x to an object whose class did not declare it",
                "class A { #x; static w(o) { o.#x = 1; } } A.w({});");
        assertJavaScriptException_ES6(
                "TypeError: Cannot read private member #m",
                "class A { #m() {} static r(o) { return o.#m(); } } A.r(Object.create(new A()));");
        assertEcmaErrorES6("TypeError", "class A { #x; static r(o) { return #x in o; } } A.r(1);");
        assertEcmaErrorES6(
                "TypeError", "class A { #x; static r(o) { return o.#x; } } A.r(undefined);");
        assertJavaScriptException_ES6(
                "TypeError",
                """
                class A { static #x = 1; static r() { return this.#x; } }
                class B extends A {}
                B.r();
                """);
    }

    @Test
    void addingTwiceThrowsTypeErrors() {
        assertJavaScriptException_ES6(
                "TypeError: Cannot initialize #x twice on the same object",
                """
                class B { constructor(o) { return o; } }
                class C extends B { #x = 1; }
                var o = {};
                new C(o); new C(o);
                """);
        assertJavaScriptException_ES6(
                "TypeError: Cannot initialize the private methods of class C twice on the same object",
                """
                class B { constructor(o) { return o; } }
                class C extends B { #m() {} }
                var o = {};
                new C(o); new C(o);
                """);
    }

    @Test
    void reflectionDoesNotSeePrivateMembers() {
        assertWithAllModes_ES6(
                "a,a,0,a,{\"a\":1},a,a,a",
                """
                class A {
                  #x = 1; #m() {} get #g() { return 1; }
                  a = 1;
                }
                var o = new A();
                var forIn = [];
                for (var k in o) forIn.push(k);
                [Object.keys(o), Object.getOwnPropertyNames(o), Object.getOwnPropertySymbols(o).length,
                 Reflect.ownKeys(o), JSON.stringify(o), forIn, Object.keys(Object.assign({}, o)),
                 Object.keys({...o})].join();
                """);
        assertWithAllModes_ES6(
                "1,",
                """
                var traps = [];
                var handler = {};
                ['get', 'set', 'has', 'defineProperty', 'getOwnPropertyDescriptor', 'ownKeys']
                  .forEach(t => handler[t] = function() {
                    traps.push(t); return Reflect[t].apply(null, arguments);
                  });
                class B { constructor(o) { return o; } }
                class C extends B { #x = 1; static get(o) { return o.#x; } }
                var p = new Proxy({}, handler);
                new C(p);
                [C.get(p), traps.join()].join();
                """);
        assertJavaScriptException_ES6(
                "TypeError",
                """
                class A { #x = 1; static get(o) { return o.#x; } }
                A.get(new Proxy(new A(), {}));
                """);
    }

    @Test
    void frozenObjectsStillGetAndUpdatePrivateFields() {
        assertWithAllModes_ES6(
                "1,2,true",
                """
                class B { constructor(o) { return o; } }
                class C extends B {
                  #x = 1;
                  static get(o) { return o.#x; }
                  static set(o, v) { o.#x = v; }
                }
                var o = Object.freeze({});
                new C(o);
                var r = [C.get(o)];
                C.set(o, 2);
                [r[0], C.get(o), Object.isFrozen(o)].join();
                """);
        assertWithAllModes_ES6(
                1,
                """
                class A { #x = 1; constructor() { Object.preventExtensions(this); } get() { return this.#x; } }
                new A().get();
                """);
    }

    @Test
    void nestedClassesAndFunctionsSeeEnclosingNames() {
        assertWithAllModes_ES6(
                "1,2,1,3",
                """
                class A {
                  #x = 1;
                  m() {
                    var self = this;
                    class B {
                      #x = 2;
                      read(o) { return o.#x; }
                      outer() { return self.#y; }
                    }
                    var b = new B();
                    function f() { return self.#x; }
                    return [f(), b.read(b), (() => this.#x)(), b.outer()].join();
                  }
                  #y = 3;
                }
                new A().m();
                """);
        assertJavaScriptException_ES6(
                "TypeError",
                """
                class A {
                  #x = 1;
                  static test() {
                    class B { #x = 2; static read(o) { return o.#x; } }
                    return B.read(new A());
                  }
                }
                A.test();
                """);
        assertEvaluatorExceptionES6(
                "Private name #z must be declared in an enclosing class",
                "class A { m() { class B { #y; } return this.#z; } }");
    }

    @Test
    void privateReferencesWorkWithEveryOperator() {
        assertWithAllModes_ES6(
                "40,6,5",
                """
                class A {
                  #x = 1;
                  #total = 0;
                  m(o) { o.#x += 2; o.#x++; [o.#x] = [o.#x * 10]; return o.#x; }
                  f() {
                    for (this.#x of [1, 2, 3]) this.#total += this.#x;
                    ({ v: this.#x } = { v: 5 });
                    return this.#total + ',' + this.#x;
                  }
                }
                [new A().m(new A()), new A().f()].join();
                """);
        assertWithAllModes_ES6(
                "undefined,1,undefined,ok",
                """
                class A {
                  #x = 1;
                  #m() { return this === a ? 'ok' : 'bad'; }
                  static read(o) { return o?.#x; }
                  static call(o) { return o?.#m(); }
                }
                var a = new A();
                [String(A.read(undefined)), A.read(a), String(A.call(null)), A.call(a)].join();
                """);
        assertWithAllModes_ES6(
                "x1",
                """
                class A {
                  #tag(strings, v) { return strings[0] + v; }
                  m() { return this.#tag`x${1}`; }
                }
                new A().m();
                """);
    }

    @Test
    void destructuringTargetsAreEvaluatedBeforeTheValue() {
        // In a derived constructor the getter binds this: reading this first must fail.
        assertWithAllModes_ES6(
                "ReferenceError,ReferenceError",
                """
                class D extends class {} {
                  #f;
                  constructor() {
                    var init = () => super();
                    ({a: this.#f} = { get a() { init(); } });
                  }
                }
                class E extends class {} {
                  constructor() {
                    var init = () => super();
                    ({a: this.f} = { get a() { init(); } });
                  }
                }
                var r = [];
                try { new D(); r.push('none'); } catch (e) { r.push(e.name); }
                try { new E(); r.push('none'); } catch (e) { r.push(e.name); }
                r.join();
                """);
        assertWithAllModes_ES6(
                "t,k,get,1",
                """
                var log = [];
                var o = { get p() { log.push('get'); return 1; } };
                var t = {};
                ({p: (log.push('t'), t)[(log.push('k'), 'q')]} = o);
                log.join() + ',' + t.q;
                """);
    }

    @Test
    void errorsAreTypeErrorsOfTheRunningRealm() {
        assertWithAllModes_ES6(
                "true,true",
                """
                class A { #m() {} static r(o) { try { o.#m(); } catch (e) { return e; } } }
                var e = A.r({});
                [e instanceof TypeError, e.constructor === TypeError].join();
                """);
    }

    @Test
    void directEvalSeesEnclosingNames() {
        assertWithAllModes_ES6(
                "1,2,3",
                """
                class A {
                  #x = 1;
                  #y = eval('this.#x + 1');
                  static #z = 3;
                  m() { return [eval('this.#x'), eval('this.#y'), eval('(() => A.#z)()')].join(); }
                }
                new A().m();
                """);
        assertEcmaErrorES6(
                "SyntaxError", "class A { #x; m() { return eval('this.#nope'); } } new A().m();");
        assertWithAllModes_ES6(
                "SyntaxError",
                """
                class A { #x; m() { return (0, eval)('this.#x'); } }
                try { new A().m(); } catch (e) { e.name }
                """);
    }

    @Test
    void javaObjectsAreBrandedByTheWrappedObject() {
        // A Java object wrapper is not a ScriptableObject: its private elements are kept for the
        // Java object, so every wrapper of the same object has them.
        assertWithAllModes_ES6(
                "false,2,true,false",
                """
                class B { constructor(o) { return o; } }
                class C extends B {
                  #y = 2;
                  static get(o) { return o.#y; }
                  static has(o) { return #y in o; }
                }
                var list = new java.util.ArrayList();
                var before = C.has(list);
                new C(list);
                var map = new java.util.HashMap();
                map.put('k', list);
                [before, C.get(map.get('k')), C.has(list), C.has(new java.util.ArrayList())].join();
                """);
        assertJavaScriptException_ES6(
                "TypeError",
                """
                class A { #x = 1; static get(o) { return o.#x; } }
                A.get(new java.lang.Object());
                """);
    }
}
