/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.13, appendix C Q27): the property key of the {@code Reflect} methods is
 * ToPropertyKey and reaches indexes, and a missing value is the plain undefined, in both modes. Not
 * related to classes.
 */
class ReflectKeyTest {

    private static final String SETUP =
            """
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void indexKeysThatAreStrings() {
        assertWithAllModes_ES6(
                "d|o|true|true|true,false|true,false|7,1|x|y",
                SETUP
                        + """
                var o = { 0: 1 }, a = [1], c = [];
                [Reflect.get(['d'], '0'), Reflect.get({ 0: 'o' }, '0'),
                  Reflect.has(['d'], '0'), Reflect.has({ 0: 1 }, '0'),
                  Reflect.deleteProperty(o, '0') + ',' + (0 in o),
                  Reflect.deleteProperty(a, '0') + ',' + (0 in a),
                  (Reflect.defineProperty(c, '0', { value: 7, writable: true, enumerable: true,
                    configurable: true }), c[0] + ',' + c.length),
                  Reflect.getOwnPropertyDescriptor(['x'], '0').value,
                  Reflect.getOwnPropertyDescriptor({ 1: 'y' }, '1').value].join('|');
                """);
    }

    @Test
    void otherKeysAreToPropertyKey() {
        // Numbers that are not indexes are names, objects go through ToPrimitive (once), and a
        // missing key is "undefined".
        assertWithAllModes_ES6(
                "f|m|big|z|sym|u|true|1",
                SETUP
                        + """
                var count = 0, s = Symbol('s'), o = { [s]: 'sym', undefined: 'u' };
                [Reflect.get({ '1.5': 'f' }, 1.5), Reflect.get({ '-1': 'm' }, -1),
                  Reflect.get({ '4294967295': 'big' }, 4294967295),
                  Reflect.get(['z'], { toString() { count++; return '0'; } }),
                  Reflect.get(o, { toString() { return s; } }), Reflect.get(o),
                  Reflect.has(o, { valueOf() { return 1; }, toString() { return s; } }),
                  count].join('|');
                """);
    }

    @Test
    void missingValuesAreUndefined() {
        assertWithAllModes_ES6(
                "undefined|undefined|[null,null]|true",
                SETUP
                        + """
                [typeof Reflect.get({}, 'x'), typeof Reflect.getOwnPropertyDescriptor({}, 'x'),
                  JSON.stringify([Reflect.get({}, 'x'), Reflect.getOwnPropertyDescriptor({}, 'x')]),
                  Reflect.get([], '0') === undefined].join('|');
                """);
    }

    @Test
    void deletePropertyDeletesOwnPropertiesOnly() {
        assertWithAllModes_ES6(
                "true,true,true,true|true,true|true,false|false|Error",
                SETUP
                        + """
                var s = Symbol(), p = { a: 1, [s]: 2 }, c = Object.create(p), q = [1], d = Object.create(q);
                var own = { [s]: 3 };
                [Reflect.deleteProperty(c, 'a') + ',' + ('a' in p) + ',' + Reflect.deleteProperty(c, s)
                    + ',' + (s in p),
                  Reflect.deleteProperty(d, '0') + ',' + (0 in q),
                  Reflect.deleteProperty(own, s) + ',' + (s in own),
                  Reflect.deleteProperty(Object.freeze({ z: 1 }), 'z'),
                  run(() => Reflect.deleteProperty(new Proxy({},
                    { deleteProperty(t, k) { throw new Error('trap:' + k); } }), 'x'))].join('|');
                """);
    }

    @Test
    void definePropertyConvertsTheKeyFirst() {
        // ToPropertyKey runs before ToPropertyDescriptor, and its error is not turned into false.
        assertWithAllModes_ES6(
                "key,desc|Error|Error",
                SETUP
                        + """
                var log = [];
                Reflect.defineProperty({}, { toString() { log.push('key'); return 'k'; } },
                  { get value() { log.push('desc'); return 1; } });
                var badKey = { toString() { throw new Error('key'); } };
                [log.join(), run(() => Reflect.defineProperty({}, badKey, {})),
                  run(() => Reflect.defineProperty({}, badKey))].join('|');
                """);
    }

    @Test
    void proxyTrapsForwardIndexesThroughReflect() {
        assertWithAllModes_ES6(
                "10,20,2|true|10,20",
                SETUP
                        + """
                var handler = {
                  get(t, k, r) { return Reflect.get(t, k, r); },
                  has(t, k) { return Reflect.has(t, k); }
                };
                var p = new Proxy([10, 20], handler);
                [p[0] + ',' + p[1] + ',' + p.length, 1 in p, [...p].join()].join('|');
                """);
    }
}
