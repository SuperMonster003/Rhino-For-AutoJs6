/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.17, appendix C Q41 / Q42 / Q43): strict mode code checks that a binding of
 * the global object is still there before writing it, a logical assignment to a name resolves the
 * binding once, and the [[Set]] of a TypedArray that is not the receiver handles numeric keys
 * itself, in both modes. Not related to classes.
 */
class SetMutableBindingAndTypedArraySetTest {

    private static final String SETUP =
            """
            var global = this;
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
            """;

    @Test
    void strictGlobalAssignmentChecksTheBinding() {
        // Upstream wrote the global object again after the binding was deleted.
        assertWithAllModes_ES6(
                "ReferenceError|false|ReferenceError|false|ReferenceError|false|ReferenceError"
                        + "|false|ReferenceError|false",
                SETUP
                        + """
                function value() { Object.defineProperty(global, 'x', { configurable: true, writable: true, value: 1 }); }
                function getter() { Object.defineProperty(global, 'x', { configurable: true, get() { delete global.x; return 2; } }); }
                var r = [];
                value(); r.push(run(function () { 'use strict'; x = (delete global.x, 2); }), 'x' in global);
                getter(); r.push(run(function () { 'use strict'; x ^= 3; }), 'x' in global);
                getter(); r.push(run(function () { 'use strict'; x++; }), 'x' in global);
                getter(); r.push(run(function () { 'use strict'; return --x; }), 'x' in global);
                getter(); r.push(run(() => { 'use strict'; x **= 2; }), 'x' in global);
                r.join('|');
                """);
    }

    @Test
    void otherGlobalAssignmentsUnchanged() {
        // Non-strict code creates the property again; declared bindings, accessors, inherited
        // properties and undeclared names behave as before.
        assertWithAllModes_ES6(
                "2|1|3|7,7|ReferenceError|1|2|true,1|11",
                SETUP
                        + """
                function value() { Object.defineProperty(global, 'x', { configurable: true, writable: true, value: 1 }); }
                function getter() { Object.defineProperty(global, 'x', { configurable: true, get() { delete global.x; return 2; } }); }
                var r = [];
                value(); r.push(run(function () { x = (delete global.x, 2); return x; }));
                getter(); r.push(run(function () { x ^= 3; return x; }));
                getter(); r.push(run(function () { x++; return x; }));
                delete global.x;
                var y = 1; let z = 1;
                r.push(run(function () { 'use strict'; y = 5; y += 1; y++; z = 5; z += 1; z++; return y + ',' + z; }));
                r.push(run(function () { 'use strict'; undeclared = 1; }), run(function () { undeclared2 = 1; return undeclared2; }));
                var seen;
                Object.defineProperty(global, 'acc', { configurable: true, get() { return 1; }, set(v) { seen = v; } });
                r.push(run(function () { 'use strict'; acc = 4; acc += 1; return seen; }));
                Object.prototype.inherited = 1;
                r.push(run(function () { 'use strict'; inherited = 2; return own(global, 'inherited') + ',' + Object.prototype.inherited; }));
                delete Object.prototype.inherited;
                r.push(run(function () { class C { m() { y = 10; y++; return y; } } return new C().m(); }));
                r.join('|');
                """);
    }

    @Test
    void logicalAssignmentInWithResolvesOnce() {
        // Upstream resolved the name again for the write: has and @@unscopables twice.
        assertWithAllModes_ES6(
                "5:has:p,get:Symbol(Symbol.unscopables),has:p,get:p,has:p,set:p"
                        + "|1:has:p,get:Symbol(Symbol.unscopables),has:p,get:p"
                        + "|5:has:p,get:Symbol(Symbol.unscopables),has:p,get:p,has:p,set:p"
                        + "|0:has:p,get:Symbol(Symbol.unscopables),has:p,get:p"
                        + "|5:has:p,get:Symbol(Symbol.unscopables),has:p,get:p,has:p,set:p"
                        + "|0:has:p,get:Symbol(Symbol.unscopables),has:p,get:p"
                        + "|1:1",
                SETUP
                        + """
                var log = [];
                function test(init, f) {
                  log = [];
                  var e = new Proxy({ p: init, [Symbol.unscopables]: undefined }, {
                    has(o, k) { log.push('has:' + String(k)); return k in o; },
                    get(o, k) { log.push('get:' + String(k)); return o[k]; },
                    set(o, k, v) { log.push('set:' + String(k)); o[k] = v; return true; }
                  });
                  return run(() => f(e)) + ':' + log.join();
                }
                var r = [
                  test(0, e => { with (e) { return p ||= 5; } }),
                  test(1, e => { with (e) { return p ||= 5; } }),
                  test(1, e => { with (e) { return p &&= 5; } }),
                  test(0, e => { with (e) { return p &&= 5; } }),
                  test(null, e => { with (e) { return p ??= 5; } }),
                  test(0, e => { with (e) { return p ??= 5; } })
                ];
                var reads = 0;
                var o = { q: 0 };
                Object.defineProperty(o, Symbol.unscopables, { get() { reads++; return {}; } });
                with (o) { q ||= 1; }
                r.push(reads + ':' + o.q);
                r.join('|');
                """);
    }

    @Test
    void logicalAssignmentWritesTheScopeItRead() {
        // A binding deleted by the right side or by a getter: non-strict code writes the object
        // again, strict mode code throws, also in eval code and in a function nested in the with
        // statement. A binding appearing during the read does not move the write. Upstream wrote
        // the scope it found after the read: the outer one, or the object the binding appeared on.
        assertWithAllModes_ES6(
                "7,outer|ReferenceError,false,outer|true,7,outer|ReferenceError,false,outer"
                        + "|ReferenceError,false,outer|ReferenceError,false,outer|true,outer|true,outer"
                        + "|5,inner|11",
                SETUP
                        + """
                var p = 'outer';
                var r = [];
                var o = { p: 0 };
                with (o) { p ||= (delete o.p, 7); }
                r.push(o.p + ',' + p);
                o = { p: 0 };
                with (o) { r.push(run(function () { 'use strict'; p ||= (delete o.p, 9); }) + ',' + own(o, 'p') + ',' + p); }
                function deleting(v) { var d = {}; Object.defineProperty(d, 'p', { configurable: true, get() { delete d.p; return v; } }); return d; }
                o = deleting(0);
                with (o) { p ||= 7; }
                r.push(own(o, 'p') + ',' + o.p + ',' + p);
                o = deleting(0);
                with (o) { r.push(run(function () { 'use strict'; p ||= 9; }) + ',' + own(o, 'p') + ',' + p); }
                o = deleting(1);
                with (o) { r.push(run(function () { 'use strict'; p &&= 9; }) + ',' + own(o, 'p') + ',' + p); }
                o = deleting(null);
                with (o) { r.push(run(function () { 'use strict'; p ??= 9; }) + ',' + own(o, 'p') + ',' + p); }
                o = deleting(0);
                with (o) { eval('p ||= 7'); }
                r.push(own(o, 'p') + ',' + p);
                o = deleting(1);
                with (o) { (function () { p &&= 8; })(); }
                r.push(own(o, 'p') + ',' + p);
                var o2 = {}, written;
                Object.defineProperty(global, 'q', { configurable: true, get() { o2.q = 'inner'; return 0; }, set(v) { written = v; } });
                with (o2) { q ||= 5; }
                r.push(written + ',' + o2.q);
                function* gen() { var w = { v: null }; with (w) { v ??= yield 'y'; } return w.v; }
                var it = gen(); it.next();
                r.push(it.next(11).value);
                r.join('|');
                """);
    }

    @Test
    void otherLogicalAssignmentsUnchanged() {
        // Locals, globals, const bindings, unresolvable names and with objects without the name.
        assertWithAllModes_ES6(
                "2,3,4|2,3,4|1|TypeError|ReferenceError|1,false",
                SETUP
                        + """
                function locals() { var a = 0, b = 1, c = null; a ||= 2; b &&= 3; c ??= 4; return [a, b, c].join(); }
                var ga = 0, gb = 1, gc = null;
                ga ||= 2; gb &&= 3; gc ??= 4;
                const k = 1;
                k ||= 2;
                var r = [locals(), [ga, gb, gc].join(), k];
                r.push(run(() => { const k0 = 0; k0 ||= 2; }), run(() => { undefinedName ||= 1; }));
                var outer = 0, empty = {};
                with (empty) { outer ||= 1; }
                r.push(outer + ',' + own(empty, 'outer'));
                r.join('|');
                """);
    }

    @Test
    void typedArrayInThePrototypeChain() {
        // An invalid index is not written, a valid one goes to the receiver, and the prototypes
        // of the TypedArray are not asked; other keys reach the setter on its prototype. Upstream
        // wrote a valid index to the TypedArray and created the other keys on the receiver.
        assertWithAllModes_ES6(
                "-1:false,7|1.5:false,7|NaN:false,7|-0:false,7|Infinity:false,7|5:false,7"
                        + "|0:true,7|01:false,7|foo:false,7|setter 2"
                        + "|1,false,a,false|1,true,false|false,true|undefined",
                SETUP
                        + """
                var r = [];
                var setterCalls = 0;
                ['-1', '1.5', 'NaN', '-0', 'Infinity', '5', '0', '01', 'foo'].forEach(k => {
                  Object.defineProperty(Int8Array.prototype, k, { configurable: true, get() { return 'proto'; }, set(v) { setterCalls++; } });
                  var ta = new Int8Array([7]);
                  var heir = Object.create(ta);
                  heir[k] = 3;
                  r.push(k + ':' + own(heir, k) + ',' + ta[0]);
                  delete Int8Array.prototype[k];
                });
                r.push('setter ' + setterCalls);
                var arr = Object.setPrototypeOf([], new Int8Array([1]));
                arr[0] = 5; arr[1] = 6;
                var str = Object.setPrototypeOf(new String(''), new Int8Array([1]));
                str[0] = 'a'; str[1] = 'b';
                r.push(arr.length + ',' + own(arr, 1) + ',' + str[0] + ',' + own(str, 1));
                var calls = 0;
                var pr = new Proxy(Object.create(new Int8Array([1])), {
                  defineProperty(t, k, d) { calls++; return Reflect.defineProperty(t, k, d); }
                });
                pr['-1'] = 1; pr[0] = 2;
                r.push(calls + ',' + own(pr, '0') + ',' + own(pr, '-1'));
                var big = Object.create(new BigInt64Array([1n]));
                big['-1'] = 1n; big[0] = 2n;
                r.push(own(big, '-1') + ',' + own(big, '0'));
                var env = Object.create(new Int32Array(10));
                Object.defineProperty(env, 'NaN', { configurable: true, value: 100 });
                with (env) { NaN = (delete env.NaN, 0); }
                r.push(String(Object.getOwnPropertyDescriptor(env, 'NaN')));
                r.join('|');
                """);
    }

    @Test
    void reflectSetWithAnotherReceiver() {
        // A TypedArray receiver defines the element, false for an invalid index; upstream
        // returned true and asked the prototypes for the other keys.
        assertWithAllModes_ES6(
                "true,true,true,true,false,true,false|7,8|0|true,false",
                SETUP
                        + """
                var ta = new Int8Array([7, 8]);
                var other = new Int8Array(1);
                var r = [[Reflect.set(ta, 0, 1, {}), Reflect.set(ta, 5, 1, {}), Reflect.set(ta, '1.5', 1, {}),
                  Reflect.set(ta, 'NaN', 1, {}), Reflect.set(ta, 1, 1, other), Reflect.set(ta, 0, 9, other),
                  Reflect.set(ta, 0, 1, Object.preventExtensions({}))].join(), ta.join()];
                r.push(other[0] === 9 ? 0 : 'other ' + other[0]);
                var receiver = {};
                r.push(Reflect.set(ta, '-0', 1, receiver) + ',' + own(receiver, '-0'));
                r.join('|');
                """);
    }

    @Test
    void typedArrayReceiverAndStrictMode() {
        // The TypedArray itself as the receiver is unchanged. In strict mode code a valid index
        // that cannot be added to the receiver throws, an invalid index is not an error.
        assertWithAllModes_ES6(
                "5,0,false,false,8|TypeError|false|false",
                SETUP
                        + """
                var t = new Int8Array(2);
                t[0] = 5; t[5] = 6; t['1.5'] = 7; t.foo = 8;
                var r = [t.join() + ',' + own(t, '5') + ',' + own(t, '1.5') + ',' + t.foo];
                var ne = Object.preventExtensions(Object.create(new Int8Array([1])));
                r.push(run(function () { 'use strict'; ne[0] = 2; }),
                  run(function () { 'use strict'; ne[3] = 2; ne['-0'] = 2; return own(ne, 3); }),
                  run(function () { ne[0] = 2; return own(ne, 0); }));
                r.join('|');
                """);
    }
}
