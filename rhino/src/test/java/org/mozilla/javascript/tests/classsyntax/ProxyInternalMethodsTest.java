/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.15, appendix C Q34): [[Get]], [[HasProperty]], [[SetPrototypeOf]],
 * [[GetOwnProperty]], [[DefineOwnProperty]] and [[OwnPropertyKeys]] of a Proxy with their
 * invariants, and the name lookup in {@code with (proxy)}, in both modes. Not related to classes.
 */
class ProxyInternalMethodsTest {

    private static final String SETUP =
            """
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            var gpo = { getPrototypeOf() { throw new Error('getPrototypeOf trap'); } };
            """;

    @Test
    void getWithoutTrapKeepsTheReceiver() {
        // Upstream gave the getter of the target the target as this, and read an inherited
        // property through the getPrototypeOf trap.
        assertWithAllModes_ES6(
                "true|true|true|7|undefined|1",
                SETUP
                        + """
                var target = { get self() { return this; } };
                var p = new Proxy(target, {});
                var heir = Object.create(p);
                var nested = new Proxy(p, {});
                var inherits = new Proxy(Object.create({ x: 7 }), gpo);
                [p.self === p, heir.self === heir, nested.self === nested,
                  inherits.x, String(inherits.y), Object.create(inherits).x === 7 ? 1 : 0].join('|');
                """);
    }

    @Test
    void hasStopsAtAProxyInTheChain() {
        // The has trap answers for the rest of the chain; without it the target's chain does.
        assertWithAllModes_ES6(
                "false|false|false|has:p,has:0,has:Symbol(s)|true|false|TypeError",
                SETUP
                        + """
                var log = [], s = Symbol('s');
                var proto = { p: 1, 0: 1, [s]: 1 };
                var proxy = new Proxy(Object.create(proto), Object.assign({
                  has(t, k) { log.push('has:' + String(k)); return false; }
                }, gpo));
                var heir = Object.create(proxy);
                var r = ['p' in heir, 0 in heir, s in heir];
                var plain = Object.create(new Proxy(Object.create({ q: 1 }), gpo));
                var fixed = Object.defineProperty({}, 'k', { value: 1 });
                var lying = Object.create(new Proxy(fixed, { has() { return false; } }));
                r.concat([log.join(), 'q' in plain, 'z' in plain, run(() => 'k' in lying)]).join('|');
                """);
    }

    @Test
    void setPrototypeOfChecksCyclesUpToAProxy() {
        // The cycle check of an ordinary object does not ask a Proxy for its prototype; the
        // result of the setPrototypeOf trap and the target's invariant are checked.
        assertWithAllModes_ES6(
                "true|ok|TypeError|false|TypeError|TypeError|setPrototypeOf,isExtensible,getPrototypeOf|true",
                SETUP
                        + """
                var arr = [];
                var r = [Object.setPrototypeOf(arr, new Proxy({}, gpo)) === arr];
                var a = {};
                r.push(run(() => { Object.setPrototypeOf(a, Object.create(new Proxy(a, {}))); return 'ok'; }));
                r.push(run(() => Object.setPrototypeOf(a, Object.create(a))));
                var refusing = new Proxy({}, { setPrototypeOf() { return false; } });
                r.push(Reflect.setPrototypeOf(refusing, {}));
                r.push(run(() => Object.setPrototypeOf(refusing, {})));
                var frozen = Object.preventExtensions({});
                r.push(run(() => Reflect.setPrototypeOf(new Proxy(frozen, { setPrototypeOf() { return true; } }), {})));
                var log = [], proto = {};
                var target = new Proxy(Object.preventExtensions(Object.create(proto)), {
                  isExtensible(t) { log.push('isExtensible'); return false; },
                  getPrototypeOf(t) { log.push('getPrototypeOf'); return proto; }
                });
                var outer = new Proxy(target, { setPrototypeOf() { log.push('setPrototypeOf'); return true; } });
                var ok = Reflect.setPrototypeOf(outer, proto);
                r.concat([log.join(), ok]).join('|');
                """);
    }

    @Test
    void getOwnPropertyDescriptorResultIsCheckedAgainstTheTarget() {
        // Upstream turned an accessor into a data property and checked no invariant.
        assertWithAllModes_ES6(
                "true|true|false,false|true|TypeError|TypeError|TypeError|TypeError|TypeError|TypeError",
                SETUP
                        + """
                var getter = function () { return 1; };
                var p = new Proxy({}, {
                  getOwnPropertyDescriptor(t, k) { return { get: getter, configurable: true }; },
                  has() { throw new Error('has trap'); }
                });
                var d = Object.getOwnPropertyDescriptor(p, 'a');
                var r = [d.get === getter, d.set === undefined, [d.enumerable, 'value' in d].join(),
                  Object.prototype.hasOwnProperty.call(p, 'a')];
                function trap(result, target) {
                  return new Proxy(target || {}, { getOwnPropertyDescriptor() { return result; } });
                }
                r.push(run(() => Object.getOwnPropertyDescriptor(trap({ value: 1, configurable: false }), 'x')));
                r.push(run(() => Object.getOwnPropertyDescriptor(trap({ value: 1, configurable: false }, { x: 1 }), 'x')));
                var writable = Object.defineProperty({}, 'x', { value: 1, writable: true });
                r.push(run(() => Object.getOwnPropertyDescriptor(
                  trap({ value: 1, writable: false, configurable: false }, writable), 'x')));
                r.push(run(() => Object.getOwnPropertyDescriptor(
                  trap({ value: 1, configurable: true }, Object.preventExtensions({})), 'x')));
                r.push(run(() => Object.getOwnPropertyDescriptor(trap({ get: 1 }), 'x')));
                r.push(run(() => Object.getOwnPropertyDescriptor(trap({ get() {}, value: 1 }), 'x')));
                r.join('|');
                """);
    }

    @Test
    void definePropertyResultAndInvariants() {
        // Reflect.defineProperty keeps the invariant errors of a Proxy and is false where the
        // ordinary target refuses; Object.defineProperty throws for a false result.
        assertWithAllModes_ES6(
                "TypeError|false|TypeError|true|true|TypeError|TypeError|true|false",
                SETUP
                        + """
                var lying = new Proxy({}, {
                  defineProperty(t, k, d) {
                    Object.defineProperty(t, k, { configurable: false, writable: true });
                    return true;
                  }
                });
                var r = [run(() => Reflect.defineProperty(lying, 'x', { writable: false }))];
                var frozen = new Proxy(new Proxy(Object.preventExtensions({}), {}), {});
                r.push(Reflect.defineProperty(frozen, 'x', { value: 1 }));
                var refusing = new Proxy(new Proxy({}, { defineProperty() { return false; } }), {});
                r.push(run(() => Object.defineProperty(refusing, 'x', { value: 1 })));
                var one = Object.defineProperty({}, 'n', { value: 1 });
                var same = new Proxy(one, { defineProperty() { return true; } });
                r.push(Reflect.defineProperty(same, 'n', { value: 0.5 + 0.5 }));
                var str = new String('ab');
                r.push(Reflect.defineProperty(new Proxy(str, {}), '0', { value: 'a' }));
                r.push(run(() => Object.defineProperty(str, '1', { value: 'x' })));
                r.push(run(() => Object.defineProperty(new Proxy(str, {}), '0', { enumerable: false })));
                r.push(Reflect.defineProperty(str, '2', { value: 'c' }));
                r.push(Reflect.defineProperty(str, '1', { value: 'x' }));
                r.join('|');
                """);
    }

    @Test
    void ownKeysOrderAndRevoke() {
        // Reflect.ownKeys keeps the order of the ownKeys trap; the revoke function is not a
        // constructor and has no prototype.
        assertWithAllModes_ES6(
                "Symbol(a),length,foo,0|false|TypeError|0",
                SETUP
                        + """
                var keys = [Symbol('a'), 'length', 'foo', '0'];
                var p = new Proxy(new Proxy([], { ownKeys() { return keys; } }), {});
                var revoke = Proxy.revocable({}, {}).revoke;
                [Reflect.ownKeys(p).map(String).join(), revoke.hasOwnProperty('prototype'),
                  run(() => new revoke()), revoke.length].join('|');
                """);
    }

    @Test
    void withProxyLooksUpThroughHasAndUnscopables() {
        // HasBinding (has, @@unscopables) then GetBindingValue (has, get); upstream used get
        // alone, so a name the Proxy has with an undefined value fell through to the outer scope.
        assertWithAllModes_ES6(
                "undefined|has:x,get:Symbol(Symbol.unscopables),has:x,get:x,has:y|outer|outer|TypeError|Error",
                SETUP
                        + """
                var log = [];
                var p = new Proxy({}, {
                  has(t, k) { log.push('has:' + String(k)); return k === 'x'; },
                  get(t, k) { log.push('get:' + String(k)); return undefined; }
                });
                var x = 'outer', y = 'outer';
                function read(o) { with (o) { return [x, y]; } }
                var r = [String(read(p)[0]), log.join()];
                var blocked = new Proxy({ y: 'inner' }, {
                  get(t, k) { return k === Symbol.unscopables ? { y: true } : t[k]; }
                });
                var hidden = new Proxy({ y: 'inner' }, { has() { return false; } });
                var fixed = new Proxy(Object.defineProperty({}, 'y', { value: 1 }), { has() { return false; } });
                var throwing = new Proxy({}, { has() { throw new Error('has'); } });
                r.concat([read(blocked)[1], read(hidden)[1], run(() => read(fixed)),
                  run(() => read(throwing))]).join('|');
                """);
    }
}
