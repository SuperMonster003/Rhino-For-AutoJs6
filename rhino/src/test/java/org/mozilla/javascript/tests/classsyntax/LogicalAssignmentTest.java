/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModesTopLevelScope_ES6;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.7, appendix C Q17): the logical assignments {@code ||=}, {@code &&=} and
 * {@code ??=} only assign when the operator does not short-circuit, in the interpreted and the
 * compiled mode.
 */
class LogicalAssignmentTest {

    private static final String ERROR =
            "function error(f) { try { f(); return 'no error'; } catch (e) { return e.name; } }\n";

    @Test
    void valuesOfTheThreeOperators() {
        assertWithAllModes_ES6(
                "5,7,9,3,0,|2,0,y",
                """
                var a = 0, b = 1, c = null, d = 3, e = 0, n = null;
                a ||= 5; b &&= 7; c ??= 9; d ||= 4; e &&= 8; n &&= 1;
                var x = 0, o = { x: 1 };
                [a, b, c, d, e, n].join() + '|' + [(x ||= 2), (o.x &&= 0), (o.y ??= 'y')].join();
                """);
    }

    @Test
    void bindingsAreOnlyWrittenWithoutShortCircuit() {
        assertWithAllModes_ES6(
                "1,0,0|TypeError|10,1,2|5,1|2|ReferenceError",
                ERROR
                        + """
                var r = [];
                const c = 1, z = 0, n = 0;
                c ||= 2; n &&= 3; z ??= 1;
                r.push([c, n, z].join());
                r.push(error(() => { const k = 0; k ||= 2; }));
                var lets = [];
                for (let i = 0; i < 3; i++) { let v = i; v ||= 10; lets.push(v); }
                r.push(lets.join());
                var w = { x: 0, y: 1 };
                with (w) { x ||= 5; y ||= 6; }
                r.push([w.x, w.y].join());
                globalThis.g1 = 0;
                g1 ||= 2;
                r.push(g1);
                r.push(error(() => { undefinedVariable ||= 1; }));
                r.join('|');
                """);
    }

    @Test
    void scriptCodeAndClosures() {
        assertWithAllModesTopLevelScope_ES6(
                "2 3|x,c0,c1,c2|8",
                """
                var o = { x: 0 }; o.x ||= 1; var k = 'x'; o[k] &&= 2; var u; u ??= 3;
                var r = [o.x + ' ' + u];
                var q = { x: 1 };
                for (var i = 0; i < 3; i++) { q['c' + i] ??= i; }
                r.push(Object.keys(q).join());
                var fs = [];
                for (let j = 0; j < 2; j++) { let p = { v: j }; p['v'] ||= 8; fs.push(() => p.v); }
                r.push(fs[0]());
                r.join('|');
                """);
    }

    @Test
    void settersAreNotCalledOnShortCircuit() {
        assertWithAllModes_ES6(
                "10|1|TypeError",
                ERROR
                        + """
                var sets = 0;
                var o = { get x() { return 1; }, set x(v) { sets++; } };
                o.x ||= 2; o.x ??= 3; o['x'] ||= 4;
                var p = { get x() { return 0; }, set x(v) { sets += 10; } };
                p.x ||= 2; p.x &&= 3;
                function strict() {
                  'use strict';
                  var f = Object.freeze({ x: 1, y: null });
                  f.x ||= 2; f.x ??= 3; f.y &&= 4;
                  return f.x;
                }
                var frozen = Object.freeze({ x: 0 });
                [sets, strict(), error(() => { 'use strict'; frozen.x ||= 2; })].join('|');
                """);
    }

    @Test
    void undefinedSetterThrowsOnWriteInStrictMode() {
        // An accessor defined with set: undefined, as test262 lgcl-*-no-set-put.js (appendix C
        // Q18).
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError,TypeError,no error,no error|2|no error,2",
                ERROR
                        + """
                var o = {};
                Object.defineProperty(o, 'p', { get() { return 2; }, set: undefined, configurable: true });
                var r = [
                  error(() => { 'use strict'; o.p = 1; }),
                  error(() => { 'use strict'; o.p += 1; }),
                  error(() => { 'use strict'; o.p &&= 1; }),
                  error(() => { 'use strict'; o['p'] ||= 1; o.p = 1; }),
                  error(() => { 'use strict'; Object.create(o).p = 1; }),
                  error(() => { 'use strict'; o.p ||= 1; o.p ??= 1; }),
                  error(() => { o.p = 1; o.p &&= 1; })
                ];
                var q = {};
                Object.defineProperty(q, 'p', { get: undefined, set: undefined, configurable: true });
                r.join() + '|' + o.p + '|' + [error(() => { q.p = 1; }), o.p].join();
                """);
    }

    @Test
    void objectAndKeyAreEvaluatedOnce() {
        assertWithAllModes_ES6(
                "obj,key,get,rhs,set,obj,key,get,obj,get,rhs3,set 9|1 1|TypeError:true",
                """
                var log = [], value = 0;
                var o = {
                  get k() { log.push('get'); return value; },
                  set k(v) { log.push('set'); value = v; }
                };
                function obj() { log.push('obj'); return o; }
                function key() { log.push('key'); return 'k'; }
                obj()[key()] ||= (log.push('rhs'), 7);
                obj()[key()] ||= (log.push('rhs2'), 8);
                obj().k &&= (log.push('rhs3'), 9);
                var r = [log.join() + ' ' + value];
                var n = 0, p = {};
                p.x ??= ++n; p.x ??= ++n;
                r.push(n + ' ' + p.x);
                var converted = false;
                var keyObject = { toString() { converted = true; return 'x'; } };
                var base = null;
                try { base[keyObject] ||= 1; } catch (e) { r.push(e.name + ':' + !converted); }
                r.join('|');
                """);
    }

    @Test
    void elementsOfArrays() {
        assertWithAllModes_ES6(
                "5,1,2|a,b",
                """
                var a = [0, 1];
                a[0] ||= 5; a[1] ||= 6; a[2] ??= 2;
                var i = 0, b = [null, null];
                b[i++] ??= 'a'; b[i++] ??= 'b';
                a.join() + '|' + b.join();
                """);
    }

    @Test
    void privateNames() {
        assertWithAllModes_ES6(
                "5,1|TypeError|7|TypeError",
                ERROR
                        + """
                class C {
                  #x = 0; #y = 1;
                  get #a() { return 1; }
                  static #s = null;
                  m() { this.#x ||= 5; this.#y ||= 6; this.#a ||= 2; this.#a ??= 3; return this.#x + ',' + this.#y; }
                  w() { this.#a &&= 2; }
                  static s() { C.#s ??= 7; C.#s ??= 8; return C.#s; }
                  static check(o) { o.#x ||= 1; }
                }
                var c = new C();
                [c.m(), error(() => c.w()), C.s(), error(() => C.check({}))].join('|');
                """);
    }

    @Test
    void superProperties() {
        assertWithAllModes_ES6(
                "3,4,0|1|q",
                """
                class A {}
                A.prototype.v = 0;
                class B extends A {
                  m() { super.v ||= 3; super['w'] ??= 4; super.v &&= 0; return [this.v, this.w, A.prototype.v].join(); }
                }
                class D extends A { constructor() { super(); super.q ||= 1; this.r = this.q; } }
                var key = 'q';
                class E extends A { constructor() { super(); super[key] ??= key; } }
                [new B().m(), new D().r, new E().q].join('|');
                """);
        // super in a let block or a with statement of a method: the compiled code used to cast the
        // scope to the activation (ClassCastException), which LogicalAssignment temps hit too.
        assertWithAllModes_ES6(
                "2|3|1|7",
                """
                var key = 'q';
                var one = { x: 1 }, three = { x: 3 };
                var o = { __proto__: one, m() { { let a = 1; var f = () => a; return super.x + f(); } } };
                var w = { __proto__: three, m() { with ({ y: 1 }) { return super.x; } } };
                class A {}
                class E extends A { constructor() { super(); { let a = key; super[a] = 1; } } }
                var p = { __proto__: {}, m() { super[key] ||= 7; return this.q; } };
                [o.m(), w.m(), new E().q, p.m()].join('|');
                """);
    }

    @Test
    void namesAreInferred() {
        assertWithAllModes_ES6(
                "f,g,h,|keep",
                """
                var f; f ||= function () {};
                var g = null; g ??= () => 1;
                var h = 1; h &&= class {};
                var o = {}; o.p ||= function () {};
                var keep = function keep() {}; var k = 0; k ||= keep;
                [f.name, g.name, h.name, o.p.name].join() + '|' + k.name;
                """);
    }

    @Test
    void generators() {
        assertWithAllModes_ES6(
                "42|1",
                """
                function* g() { var o = {}; o.x ||= yield 1; return o.x; }
                var it = g(); it.next();
                function* h() { var o = { x: 1 }; o.x ||= yield 2; return o.x; }
                var jt = h();
                it.next(42).value + '|' + jt.next().value;
                """);
    }

    @Test
    void invalidTargetsAreSyntaxErrors() {
        assertWithAllModes_ES6(
                "SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError",
                """
                var r = [];
                for (var src of ['function f() {} f() ||= 1;', 'var o = {}; o?.x ||= 1;',
                    'var o = {}; o?.[0] ??= 1;', "'use strict'; arguments ||= 1;", '1 &&= 2;',
                    '[a] ||= 1;']) {
                  try { eval(src); r.push('no error'); } catch (e) { r.push(e.name); }
                }
                r.join();
                """);
    }
}
