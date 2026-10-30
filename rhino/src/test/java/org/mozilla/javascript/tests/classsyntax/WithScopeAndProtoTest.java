/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.16, appendix C Q36 / Q37 / Q38): the bindings of a {@code with} statement
 * read {@code @@unscopables} for every object, a compound assignment and {@code typeof} resolve a
 * name once, and {@code __proto__} of a Proxy goes through its traps, in both modes. Not related to
 * classes.
 */
class WithScopeAndProtoTest {

    private static final String SETUP =
            """
            function run(f) {
              try { return String(f()); } catch (e) { return e.name + (e.message === 'boom' ? ':boom' : ''); }
            }
            """;

    @Test
    void arrayUnscopablesBlockNames() {
        // Upstream found keys, values, includes ... of Array.prototype in with (arr).
        assertWithAllModes_ES6(
                "k|v|i|e|a|f|function|2|function|k2|outer push|function",
                """
                var keys = 'k', values = 'v', includes = 'i', entries = 'e', at = 'a', flat = 'f';
                var arr = [1, 2];
                var r = [];
                with (arr) {
                  r.push(keys, values, includes, entries, at, flat, typeof push, length);
                  keys = 'k2';
                  r.push(typeof arr.keys);
                }
                r.push(keys);
                Array.prototype[Symbol.unscopables].push = true;
                var push = 'outer push';
                with (arr) { r.push(push); }
                delete Array.prototype[Symbol.unscopables].push;
                with (arr) { r.push(typeof push); }
                r.join('|');
                """);
    }

    @Test
    void unscopablesErrorsPropagate() {
        // Every form that resolves a name present on the object reads @@unscopables; an absent
        // name does not.
        assertWithAllModes_ES6(
                "Error:boom|Error:boom|Error:boom|Error:boom|Error:boom|Error:boom|undefined"
                        + "|Error:boom|Error:boom|Error:boom|1|1",
                SETUP
                        + """
                var x = 'outer';
                var env = { x: 1 };
                Object.defineProperty(env, Symbol.unscopables, { get() { throw new Error('boom'); } });
                var r = [];
                with (env) {
                  r.push(run(() => x), run(() => x()), run(() => { x = 2; return 'set'; }),
                    run(() => typeof x), run(() => x++), run(() => (x += 1)), run(() => typeof y),
                    run(() => delete x));
                }
                var blocker = Object.defineProperty({}, 'x', { get() { throw new Error('boom'); } });
                var env2 = { x: 1, [Symbol.unscopables]: blocker };
                with (env2) { r.push(run(() => x), run(() => { x = 3; return env2.x; })); }
                r.push(env.x, env2.x);
                r.join('|');
                """);
    }

    @Test
    void incrementAndDecrementResolveOnce() {
        // The getter flips its answer on each call: each operator reads @@unscopables once.
        assertWithAllModes_ES6(
                "3|6|4|1|8|4",
                """
                var calls = 0, flag = true, a, b;
                with (a = { x: 7 }) {
                  with (b = { x: 4, get [Symbol.unscopables]() { calls++; return { x: flag = !flag }; } }) {
                    x++;
                    x--;
                    --x;
                  }
                }
                var r = [calls, a.x, b.x];
                calls = 0; flag = false;
                with (a = { x: 7 }) {
                  with (b = { x: 4, get [Symbol.unscopables]() { calls++; return { x: flag = !flag }; } }) {
                    x++;
                  }
                }
                r.push(calls, a.x, b.x);
                r.join('|');
                """);
    }

    @Test
    void unscopablesOnThePrototypeChain() {
        // @@unscopables is found along the chain; a value that is not an object blocks nothing,
        // a falsy property neither; the global object can be the with object.
        assertWithAllModes_ES6(
                "outer|own y|own z|undefined|3|outer y|own|np|local z",
                """
                var x = 'outer', y = 'outer y', z = 'outer z';
                var calls = 0;
                var proto = { x: 'proto x', get [Symbol.unscopables]() { calls++; return { x: true, z: 0 }; } };
                var env = Object.create(proto);
                env.y = 'own y';
                env.z = 'own z';
                var r = [];
                with (env) { r.push(x, y, z, typeof w); }
                r.push(calls);
                var deep = Object.create(Object.create({ [Symbol.unscopables]: { y: 'yes' } }));
                deep.y = 'deep y';
                with (deep) { r.push(y); }
                var notObj = { y: 'own', [Symbol.unscopables]: 'y' };
                with (notObj) { r.push(y); }
                var nullProto = Object.create(null);
                nullProto.y = 'np';
                with (nullProto) { r.push(y); }
                globalThis[Symbol.unscopables] = { z: true };
                function f() { var z = 'local z'; with (globalThis) { return z; } }
                r.push(f());
                delete globalThis[Symbol.unscopables];
                r.join('|');
                """);
    }

    @Test
    void bindingDeletedAfterResolution() {
        // GetBindingValue and SetMutableBinding (also of x++) ask HasProperty again: ReferenceError
        // in strict
        // mode code, undefined or a new property otherwise. Upstream read and wrote the object.
        assertWithAllModes_ES6(
                "ReferenceError|ReferenceError|set|2|undefined|ReferenceError|ReferenceError|5|false"
                        + "|ReferenceError|false|3",
                SETUP
                        + """
                var called = 0;
                var env = { binding: 0, get [Symbol.unscopables]() { called++; delete env.binding; return null; } };
                var r = [];
                with (env) {
                  r.push(run(function () { 'use strict'; binding = 1; return 'set'; }));
                  env.binding = 0;
                  r.push(run(function () { 'use strict'; return binding; }));
                  env.binding = 0;
                  r.push(run(function () { binding = 2; return 'set'; }), env.binding);
                  env.binding = 0;
                  r.push(run(function () { return binding; }));
                  env.binding = 0;
                  r.push(run(function () { 'use strict'; binding += 1; return 'set'; }));
                }
                var scope = { get x() { delete this.x; return 2; } };
                with (scope) {
                  r.push(run(function () { 'use strict'; x ^= 3; return 'set'; }));
                }
                r.push(called, 'x' in scope);
                var scope2 = { get x() { delete this.x; return 2; } };
                with (scope2) { r.push(run(function () { 'use strict'; x++; return 'set'; })); }
                var scope3 = { get x() { delete this.x; return 2; } };
                with (scope3) { x++; }
                r.push('x' in scope2, scope3.x);
                r.join('|');
                """);
    }

    @Test
    void compoundAssignmentAndTypeofResolveOnce() {
        // Upstream resolved the name again for the read: has and @@unscopables twice. Other
        // scopes, generators across the read and unresolvable names keep their behaviour.
        assertWithAllModes_ES6(
                "has:p,get:Symbol(Symbol.unscopables),has:p,get:p,has:p,set:p|2"
                        + "|has:p,get:Symbol(Symbol.unscopables),has:p,get:p,has:p,set:p|4"
                        + "|has:t,has:p,get:Symbol(Symbol.unscopables),has:p,get:p|number"
                        + "|has:t,has:q|undefined|3|8|ReferenceError|undefined|17",
                SETUP
                        + """
                var log = [];
                var env = { p: 1 };
                var proxy = new Proxy(env, {
                  has(t, k) { log.push('has:' + String(k)); return Reflect.has(t, k); },
                  get(t, k, r) { log.push('get:' + String(k)); return Reflect.get(t, k, r); },
                  set(t, k, v, r) { log.push('set:' + String(k)); return Reflect.set(t, k, v, r); }
                });
                var r = [];
                with (proxy) { p += 1; }
                r.push(log.join(), env.p);
                log = [];
                with (proxy) { p **= 2; }
                r.push(log.join(), env.p);
                log = [];
                var t;
                with (proxy) { t = typeof p; }
                r.push(log.join(), t);
                log = [];
                with (proxy) { t = typeof q; }
                r.push(log.join(), t);
                var g = 1; g += 2;
                function local() { var a = 1; a += 3; with ({}) { a *= 2; } return a; }
                r.push(g, local(), run(() => { undefinedName += 1; }), typeof undefinedName);
                function* gen() { var o = { x: 10 }; with (o) { x += yield 5; } return o.x; }
                var it = gen(); it.next();
                r.push(it.next(7).value);
                r.join('|');
                """);
    }

    @Test
    void protoOfAProxyGoesThroughTraps() {
        // Upstream read and set the prototype directly, and the cycle check of an ordinary
        // object asked a Proxy in the new chain for its prototype.
        assertWithAllModes_ES6(
                "true|get:__proto__,getPrototypeOf|true|set:__proto__,setPrototypeOf|none|true|true"
                        + "|undefined|TypeError|set|ok|InternalError",
                SETUP
                        + """
                var log = [];
                var traps = {
                  get(t, k, r) { log.push('get:' + String(k)); return Reflect.get(t, k, r); },
                  set(t, k, v, r) { log.push('set:' + String(k)); return Reflect.set(t, k, v, r); },
                  getPrototypeOf(t) { log.push('getPrototypeOf'); return Reflect.getPrototypeOf(t); },
                  setPrototypeOf(t, v) { log.push('setPrototypeOf'); return Reflect.setPrototypeOf(t, v); }
                };
                var target = {}, np = {};
                var p = new Proxy(target, traps);
                var r = [p.__proto__ === Object.prototype, log.join()];
                log = [];
                p.__proto__ = np;
                r.push(Object.getPrototypeOf(target) === np, log.join());
                log = [];
                var o = {};
                o.__proto__ = new Proxy({}, traps);
                r.push(log.join() || 'none');
                var bare = new Proxy({}, {});
                bare.__proto__ = np;
                r.push(Object.getPrototypeOf(bare) === np, bare.__proto__ === np);
                r.push(String(new Proxy(Object.create(null), {}).__proto__));
                var refusing = new Proxy({}, { set() { return false; } });
                r.push(run(function () { 'use strict'; refusing.__proto__ = np; return 'set'; }));
                r.push(run(function () { refusing.__proto__ = np; return 'set'; }));
                var a = {};
                r.push(run(() => { a.__proto__ = Object.create(new Proxy(a, {})); return 'ok'; }));
                r.push(run(() => { a.__proto__ = Object.create(a); return 'ok'; }));
                r.join('|');
                """);
    }
}
