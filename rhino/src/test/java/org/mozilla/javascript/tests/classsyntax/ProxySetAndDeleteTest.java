/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.14, appendix C Q33): [[Set]] and [[Delete]] with their results, the receiver
 * of a Proxy and a Proxy in the prototype chain, in both modes. Not related to classes.
 */
class ProxySetAndDeleteTest {

    private static final String SETUP =
            """
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void setTrapForwardingWithTheReceiver() {
        // Upstream overflowed the stack: the target had no such property, so the receiver's own
        // put ran the trap again.
        assertWithAllModes_ES6(
                "2|5|10,20,30|3|a,a,sym,2,3,length,length",
                SETUP
                        + """
                var log = [];
                var handler = {
                  set(t, k, v, r) {
                    log.push(typeof k === 'symbol' ? 'sym' : k);
                    return Reflect.set(t, k, v, r);
                  }
                };
                var o = {}, po = new Proxy(o, handler), s = Symbol('s');
                po.a = 1; po.a = 2; po[s] = 5;
                var a = [10, 20], pa = new Proxy(a, handler);
                pa[2] = 30; pa.push(40); pa.length = 3;
                [o.a, o[s], a.join(), a.length, log.join()].join('|');
                """);
    }

    @Test
    void proxyInThePrototypeChain() {
        // The set trap gets the heir as the receiver; the has trap is not asked.
        assertWithAllModes_ES6(
                "set:x:true|true|false|false|TypeError",
                SETUP
                        + """
                var log = [], heir;
                var target = {};
                var proxy = new Proxy(target, {
                  set(t, k, v, r) { log.push('set:' + k + ':' + (r === heir)); return Reflect.set(t, k, v, r); },
                  has(t, k) { log.push('has:' + k); return false; }
                });
                heir = Object.create(proxy);
                heir.x = 1;
                var refusing = Object.create(new Proxy({}, { set() { return false; } }));
                refusing.y = 1;
                [log.join(), heir.hasOwnProperty('x'), 'x' in target, 'y' in refusing,
                  run(() => { 'use strict'; refusing.y = 1; })].join('|');
                """);
    }

    @Test
    void receiverProxyUsesItsDescriptorTraps() {
        // Without a set trap the target's [[Set]] keeps the Proxy as the receiver: an existing
        // property is redefined with its value only, a new one is created as a data property.
        assertWithAllModes_ES6(
                "2|3|4|false|gopd:a,define:a:value,gopd:b,define:b:value/writable/enumerable/configurable,"
                        + "gopd:c,define:c:value/writable/enumerable/configurable|true",
                SETUP
                        + """
                var log = [];
                var t = { a: 1 };
                var p = new Proxy(t, {
                  getOwnPropertyDescriptor(t, k) { log.push('gopd:' + k); return Reflect.getOwnPropertyDescriptor(t, k); },
                  defineProperty(t, k, d) { log.push('define:' + k + ':' + Object.keys(d).join('/')); return Reflect.defineProperty(t, k, d); }
                });
                p.a = 2; p.b = 3;
                var o = {};
                Reflect.set(o, 'c', 4, p);
                [t.a, t.b, t.c, 'c' in o, log.join(), Object.getOwnPropertyDescriptor(t, 'b').enumerable].join('|');
                """);
    }

    @Test
    void setterGetsTheReceiver() {
        assertWithAllModes_ES6(
                "true,true,true,true",
                SETUP
                        + """
                var self, r = [];
                var t = { set x(v) { self = this; } };
                var p = new Proxy(t, {});
                var heir = Object.create(p);
                p.x = 1; r.push(self === p);
                heir.x = 1; r.push(self === heir);
                Reflect.set(t, 'x', 1, heir); r.push(self === heir);
                var strictT = { set y(v) { 'use strict'; self = this; } };
                Reflect.set(strictT, 'y', 1, 'str'); r.push(self === 'str');
                r.join();
                """);
    }

    @Test
    void reflectSetReturnsFalse() {
        // Non-writable data, a getter only, a primitive receiver, the receiver's own accessor or
        // non-writable property, a non-extensible receiver, and a falsish trap result.
        assertWithAllModes_ES6(
                "false,false,false,false,false,false,false,1,false",
                SETUP
                        + """
                var nw = Object.defineProperty({}, 'x', { value: 1 });
                var go = { get x() { return 1; } };
                var acc = Object.defineProperty({}, 'x', { set(v) {} });
                var ro = Object.defineProperty({}, 'x', { value: 1, writable: false, configurable: true });
                var ne = Object.preventExtensions({});
                [Reflect.set(nw, 'x', 2), Reflect.set(go, 'x', 2), Reflect.set({ x: 1 }, 'x', 2, 'prim'),
                  Reflect.set({}, 'x', 2, acc), Reflect.set({ x: 1 }, 'x', 2, ro), Reflect.set({}, 'x', 2, ne),
                  Reflect.set(new Proxy({}, { set() { return 0; } }), 'x', 2), nw.x, 'x' in ne].join();
                """);
    }

    @Test
    void strictAssignmentThrowsForFalse() {
        // A false [[Set]] of a Proxy throws in strict code only; Reflect.set never throws for it.
        assertWithAllModes_ES6(
                "TypeError|TypeError|TypeError|TypeError|sloppy|1,undefined|false",
                SETUP
                        + """
                function strictSet(o, k) { 'use strict'; o[k] = 2; }
                var frozenP = new Proxy(Object.freeze({ a: 1 }), {});
                var falseP = new Proxy({}, { set() { return false; } });
                var undefinedP = new Proxy({}, { set(t, k, v) { t[k] = v; } });
                var revocable = Proxy.revocable({}, {});
                revocable.revoke();
                [run(() => strictSet(falseP, 'a')), run(() => strictSet(undefinedP, 'a')),
                  run(() => strictSet(frozenP, 'a')), run(() => { revocable.proxy.a = 1; }),
                  run(() => { falseP.a = 2; return 'sloppy'; }),
                  run(() => { frozenP.a = 2; frozenP.b = 3; return frozenP.a + ',' + frozenP.b; }),
                  run(() => { 'use strict'; return Reflect.set(Object.freeze({ a: 1 }), 'a', 2); })].join('|');
                """);
    }

    @Test
    void reflectSetLooksAtPrototypesAndExoticObjects() {
        assertWithAllModes_ES6(
                "true|true|false|true|1|true|5|true|7|true|0,1|true|9|0|true|false|false",
                SETUP
                        + """
                var calls = [], child;
                var proto = { set p(v) { calls.push(this === child); } };
                child = Object.create(proto);
                var a = [1, 2, 3], ta = new Int8Array(2), o = {};
                [Reflect.set(child, 'p', 1), calls.join(), child.hasOwnProperty('p'),
                  Reflect.set(a, 'length', 1), a.length, Reflect.set(a, '4', 5), a.length,
                  Reflect.set(ta, '0', 7), ta[0], Reflect.set(ta, '5', 7), Object.keys(ta).join(),
                  Reflect.set(ta, '1', 9, o), o[1], ta[1], Reflect.set(ta, '5', 9, o), '5' in o,
                  Reflect.set(new String('ab'), '0', 'x')].join('|');
                """);
    }

    @Test
    void setTrapInvariantsUseSameValue() {
        assertWithAllModes_ES6(
                "true|true|TypeError|TypeError|TypeError|true",
                SETUP
                        + """
                var t = {};
                Object.defineProperty(t, 'c', { value: 1 });
                Object.defineProperty(t, 'z', { value: 0 });
                Object.defineProperty(t, 'g', { get() { return 1; } });
                var p = new Proxy(t, { set() { return true; } });
                [Reflect.set(p, 'c', 1), Reflect.set(p, 'c', 2 / 2), run(() => Reflect.set(p, 'c', 2)),
                  run(() => Reflect.set(p, 'z', -0)), run(() => Reflect.set(p, 'g', 1)),
                  Reflect.set(p, 'free', 1)].join('|');
                """);
    }

    @Test
    void deleteResultsComeFromTheDeleteTrap() {
        // The has trap no longer decides the result; a non-configurable property is false for
        // Reflect.deleteProperty even in strict code, and a TypeError for the strict operator.
        assertWithAllModes_ES6(
                "true|false|false|false|a,b,b|false|true|TypeError|TypeError|false|TypeError",
                SETUP
                        + """
                var log = [];
                var t = { a: 1 };
                Object.defineProperty(t, 'n', { value: 1 });
                var p = new Proxy(t, {
                  deleteProperty(t, k) { log.push(k); return k === 'a' ? Reflect.deleteProperty(t, k) : false; },
                  has() { return true; }
                });
                var chain = new Proxy(new Proxy(t, {}), {});
                [Reflect.deleteProperty(p, 'a'), 'a' in t, Reflect.deleteProperty(p, 'b'), delete p.b,
                  log.join(), Reflect.deleteProperty(chain, 'n'), 'n' in t,
                  run(() => Reflect.deleteProperty(new Proxy(t, { deleteProperty() { return true; } }), 'n')),
                  run(() => { 'use strict'; return delete p.b; }),
                  run(() => { 'use strict'; return Reflect.deleteProperty(Object.freeze({ z: 1 }), 'z'); }),
                  run(() => { 'use strict'; var f = Object.freeze({ z: 1 }); return delete f.z; })].join('|');
                """);
    }

    @Test
    void assignmentToAPrimitive() {
        // ToObject(base).[[Set]](k, v, base): upstream threw in strict code before looking at the
        // prototypes, and elsewhere gave a setter the wrapper. Java backed setters (__proto__) get
        // the wrapper, which upstream's Reflect.set did not reach.
        assertWithAllModes_ES6(
                "number|number|boolean|1|TypeError|TypeError|TypeError|1|TypeError|ok|true|TypeError",
                SETUP
                        + """
                var self, sloppySelf, receiver, count = 0;
                Object.defineProperty(Number.prototype, 'zz', {
                  set(v) { 'use strict'; self = this; }, configurable: true
                });
                (5).zz = 1;
                sloppySelf = self;
                (function () { 'use strict'; (6).zz = 1; })();
                delete Number.prototype.zz;
                Object.setPrototypeOf(Boolean.prototype, new Proxy({}, {
                  set(t, k, v, r) { receiver = r; count++; return k === 'yes'; }
                }));
                true.yes = 1;
                var results = [typeof sloppySelf, typeof self, typeof receiver, count,
                  run(() => { 'use strict'; true.no = 1; }),
                  run(() => { 'use strict'; (5).foo = 1; }),
                  run(() => { 'use strict'; 'ab'[0] = 'x'; })];
                Object.setPrototypeOf(Boolean.prototype, Object.prototype);
                var symbolCount = 0;
                Object.setPrototypeOf(Symbol.prototype, new Proxy({}, {
                  set() { symbolCount++; return true; }
                }));
                (function () { 'use strict'; Symbol().x = 1; })();
                Object.setPrototypeOf(Symbol.prototype, Object.prototype);
                results.push(symbolCount, run(() => { 'use strict'; Symbol().x = 1; }));
                results.push(run(() => { 'use strict'; (5).__proto__ = {}; return 'ok'; }),
                  Reflect.set(Object.prototype, '__proto__', {}, 5),
                  run(() => { 'use strict'; var n = null; n.x = 1; }));
                results.join('|');
                """);
    }

    @Test
    void withProxyAsksHasBeforeTheSet() {
        // SetMutableBinding: HasProperty, then Set (upstream's NativeProxyTest checks the order).
        assertWithAllModes_ES6(
                "has:p,has:p,set:p,getOwnPropertyDescriptor:p,defineProperty:p|1",
                SETUP
                        + """
                var log = [], env = { p: 0 };
                var proxy = new Proxy(env, {
                  has(t, k) { log.push('has:' + String(k)); return Reflect.has(t, k); },
                  set(t, k, v, r) { log.push('set:' + String(k)); return Reflect.set(t, k, v, r); },
                  getOwnPropertyDescriptor(t, k) {
                    log.push('getOwnPropertyDescriptor:' + String(k));
                    return Reflect.getOwnPropertyDescriptor(t, k);
                  },
                  defineProperty(t, k, d) {
                    log.push('defineProperty:' + String(k));
                    return Reflect.defineProperty(t, k, d);
                  }
                });
                with (proxy) { p = 1; }
                log.join() + '|' + env.p;
                """);
    }

    @Test
    void copyWithinAsksHasProperty() {
        // Upstream deleted a target whose source held undefined, and only asked the has trap of a
        // Proxy through the assignment, which no longer does (P6.14).
        assertWithAllModes_ES6(
                "true:undefined|false|has:0,get:0,set:1|Error",
                SETUP
                        + """
                var o = { 0: undefined, 1: 1, length: 2 };
                Array.prototype.copyWithin.call(o, 1, 0);
                var hole = { 1: 1, length: 2 };
                Array.prototype.copyWithin.call(hole, 1, 0);
                var log = [];
                var p = new Proxy({ 0: 'a', length: 2 }, {
                  has(t, k) { log.push('has:' + k); return k in t; },
                  get(t, k, r) { if (k !== 'length') log.push('get:' + k); return t[k]; },
                  set(t, k, v) { log.push('set:' + k); t[k] = v; return true; }
                });
                Array.prototype.copyWithin.call(p, 1, 0);
                var throwing = new Proxy({ 0: 42, length: 1 }, { has() { throw new Error(); } });
                [o.hasOwnProperty(1) + ':' + o[1], hole.hasOwnProperty(1), log.join(),
                  run(() => Array.prototype.copyWithin.call(throwing, 0, 0))].join('|');
                """);
    }
}
