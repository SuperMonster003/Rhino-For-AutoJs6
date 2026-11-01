/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaConstructor;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.testutils.Utils;

/**
 * AutoJs6 class syntax (ROADMAP P4): the built-in constructors create their instances from
 * new.target (OrdinaryCreateFromConstructor, ROADMAP T9), so a class extending a built-in gets an
 * instance of the built-in with the prototype of the subclass. One group per constructor of ROADMAP
 * appendix D, each case in the interpreted and the compiled mode.
 */
class BuiltinSubclassingTest {

    @Test
    void object() {
        assertWithAllModes_ES6(
                "object,true,true,0,1",
                """
                class X extends Object { constructor() { super(5); } m() { return 1; } }
                var x = new X();
                [typeof x, x instanceof X, x.valueOf() === x, Object.keys(x).length, x.m()].join();
                """);
        assertWithAllModes_ES6(
                "true,true",
                """
                function F() {}
                var o = Reflect.construct(Object, [1], F);
                [Object.getPrototypeOf(o) === F.prototype, Object.getPrototypeOf(new Object()) === Object.prototype].join();
                """);
    }

    @Test
    void functionAndGeneratorFunction() {
        assertWithAllModes_ES6(
                "2,true,true,3,function,1",
                """
                class F extends Function { twice(x) { return this(this(x)); } }
                var f = new F('a', 'return a + 1');
                [f(1), f instanceof F, f instanceof Function, f.twice(1), typeof f, f.length].join();
                """);
        assertWithAllModes_ES6(
                "1,true,false,true",
                """
                var GF = Object.getPrototypeOf(function* () {}).constructor;
                class G extends GF {}
                var g = new G('yield 1');
                [g().next().value, g instanceof G, Object.hasOwn(g.prototype, 'constructor'),
                 Object.getPrototypeOf(g.prototype) === GF.prototype.prototype].join();
                """);
    }

    @Test
    void array() {
        assertWithAllModes_ES6(
                "6,2,true,true,6",
                """
                class A extends Array { sum() { return this.reduce((a, b) => a + b, 0); } }
                var a = new A();
                a[5] = 1;
                var before = a.length;
                a.length = 2;
                [before, a.length, Array.isArray(a), a instanceof A, new A(1, 2, 3).sum()].join();
                """);
        assertWithAllModes_ES6(
                "true,true,true,true,true,true,3",
                """
                class A extends Array {}
                var a = new A(1, 2, 3);
                [A.from([1]) instanceof A, A.of(1) instanceof A, a.map(x => x) instanceof A,
                 a.filter(x => x) instanceof A, a.slice(0) instanceof A, a.concat([4]) instanceof A,
                 new A(3).length].join();
                """);
    }

    @Test
    void errors() {
        assertWithAllModes_ES6(
                "true,true,boom,E: boom,string,[object Error],true,string",
                """
                class E extends Error { constructor(m) { super(m); this.name = 'E'; } }
                var e = new E('boom');
                var o = {};
                Error.captureStackTrace(o);
                [e instanceof E, e instanceof Error, e.message, String(e), typeof e.stack,
                 Object.prototype.toString.call(e), Object.hasOwn(e, 'message'), typeof o.stack].join();
                """);
        assertWithAllModes_ES6(
                "EvalError,RangeError,ReferenceError,SyntaxError,TypeError,URIError,InternalError",
                """
                [EvalError, RangeError, ReferenceError, SyntaxError, TypeError, URIError, InternalError]
                  .map(C => {
                    class X extends C {}
                    var x = new X('m');
                    var ok = x instanceof X && x instanceof C && x.message === 'm'
                      && Object.getPrototypeOf(X.prototype) === C.prototype;
                    return ok ? x.name : 'fail ' + C.name;
                  }).join();
                """);
        assertWithAllModes_ES6(
                "true,2,m,true",
                """
                class X extends AggregateError {}
                var x = new X([1, 2], 'm', { cause: 0 });
                [x instanceof X, x.errors.length, x.message, Object.hasOwn(x, 'cause')].join();
                """);
    }

    @Test
    void primitiveWrappers() {
        assertWithAllModes_ES6(
                "false,true,6,6.0,true,2,b,AB,true,object",
                """
                class B extends Boolean {}
                class N extends Number {}
                class S extends String {}
                var s = new S('ab');
                [new B(false).valueOf(), new B(false) instanceof B, new N(5) + 1, new N(6).toFixed(1),
                 new N(1) instanceof N, s.length, s[1], s.toUpperCase(), s instanceof S,
                 typeof new N(1)].join();
                """);
    }

    @Test
    void symbolAndBigIntAreNotConstructors() {
        assertWithAllModes_ES6(
                "TypeError,TypeError",
                """
                class X extends Symbol {}
                class Y extends BigInt {}
                var r = [];
                try { new X(); } catch (e) { r.push(e.name); }
                try { new Y(1); } catch (e) { r.push(e.name); }
                r.join();
                """);
    }

    @Test
    void date() {
        assertWithAllModes_ES6(
                "1970,true,true,string",
                """
                class D extends Date { year() { return this.getUTCFullYear(); } }
                var d = new D(0);
                [d.year(), d instanceof D, d instanceof Date, typeof d.toISOString()].join();
                """);
    }

    @Test
    void regExp() {
        assertWithAllModes_ES6(
                "true,1,2,true,2,g,b",
                """
                class R extends RegExp {
                  exec(s) { this.called = (this.called | 0) + 1; return super.exec(s); }
                }
                var r = new R('b', 'g');
                var m = r.test('abc');
                [m, r.called, r.lastIndex, r instanceof R, 'a,b'.split(new R(',')).length, r.flags,
                 r.source].join();
                """);
        assertWithAllModes_ES6(
                "false,true,true,i,A",
                """
                class R extends RegExp {}
                var r = new R('a', 'i');
                var c = new R(r);
                [RegExp(r) === r, RegExp(/a/) instanceof RegExp, c instanceof R, c.flags,
                 r[Symbol.match]('A')[0]].join();
                """);
    }

    @Test
    void mapAndSetUseTheOverriddenAdders() {
        assertWithAllModes_ES6(
                "2,1|3,20",
                """
                class M extends Map { set(k, v) { (this.log = this.log || []).push(k); return super.set(k, v * 10); } }
                var m = new M([[1, 2], [3, 4]]);
                [m.size, m.log.join('|'), m.get(1)].join();
                """);
        assertWithAllModes_ES6(
                "2,1|2,true",
                """
                class S extends Set { add(v) { (this.log = this.log || []).push(v); return super.add(v); } }
                var s = new S([1, 2]);
                [s.size, s.log.join('|'), s instanceof S].join();
                """);
        assertWithAllModes_ES6(
                "1,1,1,true",
                """
                class WM extends WeakMap { set(k, v) { this.n = (this.n | 0) + 1; return super.set(k, v); } }
                class WS extends WeakSet { add(v) { this.n = (this.n | 0) + 1; return super.add(v); } }
                var k = {};
                var wm = new WM([[k, 1]]);
                var ws = new WS([k]);
                [wm.n, wm.get(k), ws.n, ws.has(k)].join();
                """);
        assertWithAllModes_ES6(
                "TypeError",
                """
                class M extends Map { get set() { return 1; } }
                try { new M([]); } catch (e) { e.name; }
                """);
    }

    @Test
    void weakRefAndFinalizationRegistry() {
        assertWithAllModes_ES6(
                "true,true,true,function",
                """
                class W extends WeakRef {}
                class F extends FinalizationRegistry {}
                var o = {};
                var w = new W(o);
                var f = new F(() => {});
                [w.deref() === o, w instanceof W, f instanceof F, typeof f.register].join();
                """);
    }

    @Test
    void promise() {
        assertWithAllModes_ES6(
                "true,true,true,true,true,1",
                """
                class P extends Promise {}
                var seen;
                var p = new P(r => { seen = 1; r(1); });
                [p instanceof P, p.then(x => x) instanceof P, P.resolve(1) instanceof P,
                 P.all([]) instanceof P, p.finally(() => 1) instanceof P, seen].join();
                """);
        assertWithAllModes_ES6(
                true,
                """
                function F() {}
                Object.getPrototypeOf(Reflect.construct(Promise, [() => {}], F)) === F.prototype;
                """);
    }

    @Test
    void proxyIgnoresNewTarget() {
        assertWithAllModes_ES6(
                "TypeError,true",
                """
                var r;
                try { class X extends Proxy {} } catch (e) { r = e.name; }
                var t = Object.create(Array.prototype);
                var p = Reflect.construct(Proxy, [t, {}], function () {});
                [r, Object.getPrototypeOf(p) === Array.prototype].join();
                """);
    }

    @Test
    void arrayBufferAndDataView() {
        assertWithAllModes_ES6(
                "8,true,true,true",
                """
                class B extends ArrayBuffer {}
                var b = new B(8);
                [b.byteLength, b instanceof B, b.slice(0, 2) instanceof B, B[Symbol.species] === B].join();
                """);
        assertWithAllModes_ES6(
                "8,true,true,true",
                """
                class B extends SharedArrayBuffer {}
                var b = new B(8);
                [b.byteLength, b instanceof B, b.slice(0, 2) instanceof B, B[Symbol.species] === B].join();
                """);
        assertWithAllModes_ES6(
                "7,true,3",
                """
                class V extends DataView {}
                var v = new V(new ArrayBuffer(8), 1);
                v.setInt8(0, 7);
                [v.getInt8(0), v instanceof V, new V(new ArrayBuffer(8), 2, 3).byteLength].join();
                """);
    }

    @Test
    void typedArrays() {
        assertWithAllModes_ES6(
                "10,0",
                """
                var ok = 0;
                var types = [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array,
                  Int32Array, Uint32Array, Float16Array, Float32Array, Float64Array];
                for (var C of types) {
                  class X extends C { first() { return this[0]; } }
                  var x = new X(4);
                  x[0] = 3;
                  if (x.length === 4 && x instanceof X && x instanceof C && x.first() === 3
                      && x.map(v => v) instanceof X && x.subarray(1) instanceof X
                      && x.slice(1) instanceof X && X.from([1, 2]) instanceof X
                      && X.of(1) instanceof X) {
                    ok++;
                  }
                }
                [ok, new (class extends Int8Array {})().length].join();
                """);
        assertWithAllModes_ES6(
                "true,true,true,3",
                """
                class X extends BigInt64Array {}
                class Y extends BigUint64Array {}
                var x = X.of(1n, 2n, 3n);
                [x instanceof X, x.map(v => v) instanceof X, Y.from([1n]) instanceof Y, x.length].join();
                """);
        assertWithAllModes_ES6(
                "TypeError",
                """
                var TA = Object.getPrototypeOf(Int8Array);
                try { Reflect.construct(TA, [], Int8Array); } catch (e) { e.name; }
                """);
        // fromBase64 and fromHex always create a Uint8Array, also when called on a subclass.
        assertWithAllModes_ES6(
                "2,true,3,true",
                """
                class X extends Uint8Array {}
                var a = X.fromHex('ff00');
                var b = Uint8Array.fromBase64('x+/y');
                [a.length, Object.getPrototypeOf(a) === Uint8Array.prototype,
                 b.length, Object.getPrototypeOf(b) === Uint8Array.prototype].join();
                """);
    }

    @Test
    void rhinoConstructors() {
        assertWithAllModes_ES6(
                "true,true,true,true",
                """
                class I extends Iterator {}
                class S extends Script {}
                class C extends CallSite {}
                class J extends JavaImporter {}
                [new I({ a: 1 }) instanceof I, new S('1 + 1') instanceof S, new C() instanceof C,
                 new J() instanceof J].join();
                """);
    }

    @Test
    void newTargetOtherThanAClass() {
        assertWithAllModes_ES6(
                "true,true,true,true",
                """
                var proto = {};
                var p = new Proxy(function () {}, { get: (t, k) => k === 'prototype' ? proto : t[k] });
                var b = function () {}.bind();
                class A {}
                [Object.getPrototypeOf(Reflect.construct(Map, [], p)) === proto,
                 Object.getPrototypeOf(Reflect.construct(A, [], p)) === proto,
                 Object.getPrototypeOf(Reflect.construct(Map, [], b)) === Map.prototype,
                 Object.getPrototypeOf(Reflect.construct(A, [], b)) === Object.prototype].join();
                """);
        assertWithAllModes_ES6(
                "true,true",
                """
                class A extends Map {}
                class B extends A { constructor() { super([[1, 2]]); } }
                var b = new B();
                [b instanceof B && b instanceof A && b instanceof Map, b.get(1) === 2].join();
                """);
    }

    @Test
    void prototypeFromTheRealmOfNewTarget() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    TopLevel scope = cx.initStandardObjects();
                    TopLevel other = cx.initStandardObjects();
                    Object fn =
                            cx.evaluateString(
                                    other,
                                    "var f = function () {}; f.prototype = null; f",
                                    "other.js",
                                    1,
                                    null);
                    ScriptableObject.putProperty(scope, "other", fn);
                    ScriptableObject.putProperty(scope, "otherGlobal", other);
                    Object result =
                            cx.evaluateString(
                                    scope,
                                    """
                                    var g = otherGlobal;
                                    var b = other.bind();
                                    [Object.getPrototypeOf(Reflect.construct(Map, [], other)) === g.Map.prototype,
                                     Object.getPrototypeOf(Reflect.construct(TypeError, [], other)) === g.TypeError.prototype,
                                     Object.getPrototypeOf(Reflect.construct(Array, [], b)) === g.Array.prototype,
                                     Object.getPrototypeOf(Reflect.construct(Object, [], other)) === g.Object.prototype,
                                     Object.getPrototypeOf(Reflect.construct(class {}, [], other)) === g.Object.prototype].join();
                                    """,
                                    "test.js",
                                    1,
                                    null);
                    assertEquals("true,true,true,true,true", result);
                    return null;
                });
    }

    @Test
    void hostLambdaConstructor() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    TopLevel scope = cx.initStandardObjects();
                    LambdaConstructor ctor =
                            new LambdaConstructor(
                                    scope,
                                    "Host",
                                    0,
                                    (lcx, nt, s, args) -> {
                                        NativeObject o = new NativeObject();
                                        o.put("tag", o, "host");
                                        return o;
                                    });
                    ScriptableObject.defineProperty(scope, "Host", ctor, ScriptableObject.DONTENUM);
                    Object result =
                            cx.evaluateString(
                                    scope,
                                    """
                                    class X extends Host { m() { return 1; } }
                                    var x = new X();
                                    [x.tag, x instanceof X, x instanceof Host, x.m(),
                                     Object.getPrototypeOf(new Host()) === Host.prototype].join();
                                    """,
                                    "test.js",
                                    1,
                                    null);
                    assertEquals("host,true,true,1,true", result);
                    return null;
                });
    }
}
