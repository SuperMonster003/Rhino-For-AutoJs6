/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.9, appendix C Q19): the fast path of spreading an array, {@code [...a]} and
 * the spread arguments of calls, which copies the elements when iterating them can not be observed.
 * Each script compares a spread that may take the fast path with one of {@code twin(a)}, the same
 * elements with the built-in iterator as an own property, which always iterates.
 */
class ArraySpreadTest {

    private static final String SETUP =
            """
            function twin(a) { var t = a.slice(); t[Symbol.iterator] = Array.prototype.values; return t; }
            function args() { return Array.prototype.join.call(arguments, ','); }
            var arrayIteratorProto = Object.getPrototypeOf([][Symbol.iterator]());
            class Base { m(...xs) { return 'm:' + xs.join(); } }
            class Sub extends Base { m(...xs) { return super.m(...xs); } }
            function Ctor(...xs) { this.s = xs.join(); }
            var o = { m: args };
            // every spread form for the elements of a, as one string
            function forms(a) {
              return [[...a].join(), [0, ...a].join(), args(...a), args(0, ...a, ...a), o.m(...a),
                new Sub().m(...a), new Ctor(...a).s].join('|');
            }
            """;

    @Test
    void plainArraysTakeTheFastPath() {
        assertArrayEquals(new Object[] {"a", "b", "c"}, elements("", "['a', 'b', 'c']"));
        assertArrayEquals(new Object[0], elements("", "[]"));
        assertArrayEquals(
                new Object[] {Undefined.instance, null},
                elements("", "var a = [undefined, null, 5]; a.pop(); a"));
        assertArrayEquals(new Object[] {"x"}, elements("", "var a = []; a.x = 1; a.push('x'); a"));
    }

    @Test
    void otherValuesIterate() {
        String[] values = {
            "[1, , 3]",
            "var a = [1, 2]; a.length = 3; a",
            "(function () { return arguments; })(1, 2)",
            "({ length: 2, 0: 1, 1: 2 })",
            "'ab'",
            "new Set([1, 2])",
            "new Int8Array(2)",
            "new Proxy([1, 2], {})",
            "var a = [1, 2]; a[Symbol.iterator] = Array.prototype.values; a",
            "var a = [1, 2]; Object.defineProperty(a, 0, { get() { return 1; } }); a",
            "Object.setPrototypeOf([1, 2], Object.create(Array.prototype))",
            "class A extends Array {}; A.of(1, 2)",
            "Array.prototype"
        };
        for (String value : values) {
            assertNull(elements("", value), value);
        }
    }

    @Test
    void overridesIterate() {
        String[] setups = {
            "Array.prototype[Symbol.iterator] = function () { return [].values.call(this); };",
            "Object.defineProperty(Array.prototype, Symbol.iterator, { get() { return [].values; } });",
            "var p = Object.getPrototypeOf([].values()); var n = p.next; p.next = function () { return n.call(this); };",
            "Object.getPrototypeOf([].values()).return = function () { return {}; };",
            "Object.setPrototypeOf(Object.getPrototypeOf([].values()), {});",
            "Object.prototype.return = undefined;",
            "Object.defineProperty(Object.prototype, 'value', { set(v) {}, configurable: true });",
            "Object.defineProperty(Object.prototype, 'done', { value: false, writable: true });"
        };
        for (String setup : setups) {
            assertNull(elements(setup, "[1, 2]"), setup);
        }
    }

    @Test
    void assigningTheBuiltinsAgainKeepsTheFastPath() {
        String[] setups = {
            "Array.prototype[Symbol.iterator] = Array.prototype.values;",
            "var p = Object.getPrototypeOf([].values()); p.next = p.next;",
            "var v = Array.prototype.values; Array.prototype[Symbol.iterator] = 1;"
                    + " Array.prototype[Symbol.iterator] = v;"
        };
        for (String setup : setups) {
            assertArrayEquals(new Object[] {"a"}, elements(setup, "['a']"), setup);
        }
    }

    @Test
    void anotherRealmIterates() {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            TopLevel scope = cx.initStandardObjects();
            TopLevel other = cx.initStandardObjects();
            Object array = cx.evaluateString(scope, "['a', 'b']", "test", 1, null);
            assertArrayEquals(new Object[] {"a", "b"}, ArraySpread.elements(array, scope));
            assertNull(ArraySpread.elements(array, other));
        }
    }

    @Test
    void elementsAsTheProtocolGivesThem() {
        assertWithAllModes_ES6(
                true,
                SETUP
                        + """
                var arrays = [[], [1], [1, 2, 3], [undefined, null, NaN, -0, 'x', {}, [1]],
                  Array.from({ length: 300 }, (_, i) => i * i), new Array(3).fill(7), 'abc'.split('')];
                arrays.every(function (a) {
                  var manual = [], it = Array.prototype.values.call(a), r;
                  while (!(r = it.next()).done) manual.push(r.value);
                  return forms(a) === forms(twin(a)) && [...a].length === manual.length
                    && [...a].every((v, i) => Object.is(v, manual[i]));
                });
                """);
    }

    @Test
    void overridingTheIteratorIsObserved() {
        assertWithAllModes_ES6(
                "1,2|1,2|x,y|x,y|2|1,2|1,2",
                SETUP
                        + """
                var a = [1, 2], out = [], calls = 0;
                var values = Array.prototype[Symbol.iterator];
                for (var i = 0; i < 4; i++) {
                  if (i === 2) Array.prototype[Symbol.iterator] = function* () { calls++; yield 'x'; yield 'y'; };
                  out.push(args(...a));
                }
                out.push(calls);
                Array.prototype[Symbol.iterator] = values;
                out.push([...a].join(), args(...a));
                out.join('|');
                """);
    }

    @Test
    void overridingNextIsObserved() {
        assertWithAllModes_ES6(
                "27|27|0|true",
                SETUP
                        + """
                // forms runs 9 spreads of 2 elements: 3 calls of next each
                var a = [1, 2], out = [], calls = 0;
                var next = arrayIteratorProto.next;
                arrayIteratorProto.next = function () { calls++; return next.call(this); };
                forms(a);
                out.push(calls);
                calls = 0;
                forms(twin(a));
                out.push(calls);
                arrayIteratorProto.next = next;
                calls = 0;
                forms(a);
                out.push(calls, forms(a) === forms(twin(a)));
                out.join('|');
                """);
    }

    @Test
    void changesBetweenSpreadsOfOneCallAreObserved() {
        assertWithAllModes_ES6(
                "1,2,x,y,x,y,1,2",
                SETUP
                        + """
                var a = [1, 2], values = Array.prototype[Symbol.iterator];
                function swap() { Array.prototype[Symbol.iterator] = function* () { yield 'x'; yield 'y'; }; return []; }
                function back() { Array.prototype[Symbol.iterator] = values; return []; }
                args(...a, ...swap(), ...a, ...back(), ...a);
                """);
    }

    @Test
    void returnAndResultPropertiesAsTheProtocolHasThem() {
        // These send spreads through the protocol, which since P6.10 neither looks up nor calls
        // return when the iterator is done and writes the results as own data properties: fast
        // and slow give the plain elements and log nothing.
        assertWithAllModes_ES6(
                "true|true|true|true",
                SETUP
                        + """
                function same(setup, undo) {
                  var log = [];
                  setup(log);
                  var fast = forms([1, 2]) + log.join();
                  log.length = 0;
                  var slow = forms(twin([1, 2])) + log.join();
                  undo();
                  return fast === slow && fast === plain;
                }
                var plain = forms([1, 2]);
                [same(log => { arrayIteratorProto.return = function () { log.push('r'); return {}; }; },
                      () => { delete arrayIteratorProto.return; }),
                 same(log => { Object.defineProperty(Object.prototype, 'return',
                        { get() { log.push('g'); }, configurable: true }); },
                      () => { delete Object.prototype.return; }),
                 same(log => { Object.defineProperty(Object.prototype, 'value',
                        { get() { log.push('v'); return 'v'; }, set(v) { log.push('s'); }, configurable: true }); },
                      () => { delete Object.prototype.value; }),
                 same(log => { Object.defineProperty(Object.prototype, 'done',
                        { get() { log.push('d'); return true; }, set(v) {}, configurable: true }); },
                      () => { delete Object.prototype.done; })].join('|');
                """);
    }

    @Test
    void holesAccessorsAndArrayLikesIterate() {
        assertWithAllModes_ES6(
                "true|true|1,2|a,b|1,2|1,2",
                SETUP
                        + """
                var holes = [1, , 3], holesTwin = twin(holes);
                Array.prototype[1] = 'p';
                var r1 = forms(holes) === forms(holesTwin);
                delete Array.prototype[1];
                var gets = 0, acc = [1, 2, 3];
                Object.defineProperty(acc, 1, { get() { gets++; return 'g'; } });
                var accTwin = twin(acc);
                gets = 0;
                // 8 of the spreads of forms spread acc
                var r2 = forms(acc) === forms(accTwin) && gets === 8;
                var like = { length: 2, 0: 1, 1: 2, [Symbol.iterator]: Array.prototype.values };
                function argsOf() { return args(...arguments); }
                [r1, r2, args(...like), args(...'ab'), argsOf(1, 2), args(...new Set([1, 2, 1]))]
                  .join('|');
                """);
    }

    @Test
    void evaluationOrderAndErrors() {
        assertWithAllModes_ES6(
                "1,2,3|4,1,2,3,9|TypeError,TypeError,TypeError,TypeError,TypeError|true",
                SETUP
                        + """
                function error(f) { try { f(); return 'none'; } catch (e) { return e.name; } }
                function message(f) { try { f(); } catch (e) { return e.message; } }
                var a = [1, 2, 3];
                var r1 = args(...a, a.push(9) && '').replace(/,$/, '');
                a.length = 3;
                var r2 = args(a.push(9), ...a);
                function num() { var n = 1; n = n + 1; return args(...n); }
                function run() {
                  var n = 1;
                  return [error(() => args(...null)), error(() => args(...undefined)),
                    error(() => args(...n)), error(num), error(() => new Ctor(...{}))].join();
                }
                var sameMessages = [null, undefined, 1, {}].every(
                  v => message(() => args(...v)) === message(() => [...v]));
                [r1, r2, run(), sameMessages].join('|');
                """);
    }

    @Test
    void spreadValuesFromYieldInGenerators() {
        assertWithAllModes_ES6(
                "1,2|3,4|m:5,6",
                SETUP
                        + """
                function* g() {
                  var r = args(...(yield 1));
                  var s = o.m(...(yield r));
                  return [r, s, new Sub().m(...(yield))].join('|');
                }
                var it = g();
                it.next();
                it.next([1, 2]);
                it.next([3, 4]);
                it.next([5, 6]).value;
                """);
    }

    private static Object[] elements(String setup, String value) {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            TopLevel scope = cx.initStandardObjects();
            cx.evaluateString(scope, setup, "setup", 1, null);
            Object array = cx.evaluateString(scope, value, "value", 1, null);
            return ArraySpread.elements(array, scope);
        }
    }
}
