/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.18, appendix C Q44): the seven internal methods of a TypedArray for the
 * CanonicalNumericIndexString keys, "NaN" among them, in both modes. Not related to classes.
 */
class TypedArrayInternalMethodsTest {

    private static final String SETUP =
            """
            var TA = Object.getPrototypeOf(Int8Array);
            var keys = ['NaN', '1.5', '-0', '-1', 'Infinity', '-Infinity', '1e+21', '2', '4294967296'];
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            function each(f) { return keys.map(f).join(); }
            function trap(k) {
              Object.defineProperty(TA.prototype, k, {
                configurable: true, get() { throw new Error('asked ' + k); }, set(v) { throw new Error('asked ' + k); }
              });
            }
            function untrap(k) { delete TA.prototype[k]; }
            var kinds = [new Int8Array([1, 2]), new Float64Array([1, 2]), new BigInt64Array([1n, 2n])];
            """;

    private static String allFalse() {
        return "false,false,false,false,false,false,false,false,false";
    }

    private static String allTrue() {
        return "true,true,true,true,true,true,true,true,true";
    }

    @Test
    void getDoesNotAskThePrototypes() {
        // Upstream went on to the prototypes for every key but the valid indices.
        assertWithAllModes_ES6(
                "true,true,true|undefined,undefined,undefined",
                SETUP
                        + """
                keys.forEach(trap);
                var r = kinds.map(function (ta) { return keys.every(function (k) { return ta[k] === undefined; }); });
                var numbers = [NaN, 1.5, -1].map(function (k) { return String(kinds[0][k]); });
                keys.forEach(untrap);
                r.join() + '|' + numbers.join();
                """);
    }

    @Test
    void getOfValidIndexAndNumberAndStringForms() {
        assertWithAllModes_ES6(
                "2,2,2,1|2n|1,1|5,undefined|undefined",
                SETUP
                        + """
                var ta = kinds[0], big = kinds[2];
                var h = Object.create(ta);
                h[0] = 5;
                h[7] = 6;
                var r = [[ta[1], ta['1'], Reflect.get(ta, '1'), ta[-0]].join()];
                r.push(big['1'] + 'n');
                r.push([Reflect.get(ta, 0, {}), Object.create(ta)[0]].join());
                r.push([h[0], String(h['7'])].join());
                r.push(String(Object.create(ta)['1.5']));
                r.join('|');
                """);
    }

    @Test
    void hasProperty() {
        // Upstream answered true for "NaN" once it was set, and from the prototypes otherwise.
        assertWithAllModes_ES6(
                allFalse() + "|" + allFalse() + "|" + allFalse() + "|true,true,true,false",
                SETUP
                        + """
                var ta = kinds[0], heir = Object.create(ta);
                keys.forEach(function (k) { TA.prototype[k] = 1; });
                var r = [
                  each(function (k) { return k in ta; }),
                  each(function (k) { return Reflect.has(ta, k); }),
                  each(function (k) { return k in heir; })
                ];
                keys.forEach(untrap);
                r.push([0 in ta, '1' in kinds[1], Reflect.has(kinds[2], '0'), 2 in kinds[2]].join());
                r.join('|');
                """);
    }

    @Test
    void hasPropertyWithAProxyCreated() {
        // Once a Proxy exists another path looks along the prototype chain.
        assertWithAllModes_ES6(
                allFalse() + "|true|false",
                SETUP
                        + """
                new Proxy({}, {});
                var ta = kinds[0];
                keys.forEach(function (k) { TA.prototype[k] = 1; });
                var r = [each(function (k) { return k in Object.create(ta); })];
                keys.forEach(untrap);
                r.push(0 in Object.create(ta), 2 in Object.create(ta));
                r.join('|');
                """);
    }

    @Test
    void setAsReceiverWritesNothingForAnInvalidIndex() {
        // Upstream created a property for "NaN" and skipped the conversion of the value.
        assertWithAllModes_ES6(
                "1,2|0,0,0,0,0,0,0,0,0|"
                        + allTrue()
                        + "|9,2|TypeError|42,2|Error,Error,Error,Error,Error,Error,Error,Error,Error",
                SETUP
                        + """
                keys.forEach(trap);
                var ta = new Int8Array([1, 2]), count = 0;
                var v = { valueOf() { count++; return 7; } };
                keys.forEach(function (k) { ta[k] = v; });
                var r = [[ta[0], ta[1]].join()];
                r.push(keys.map(function (k) { return Object.keys(ta).indexOf(k) + 1; }).join());
                r.push(each(function (k) { return Reflect.set(ta, k, 3); }));
                ta[0] = 9;
                r.push([ta[0], ta[1]].join());
                var big = new BigInt64Array([1n, 2n]);
                r.push(run(function () { big['1.5'] = 1; }));
                big['1'] = 42n;
                r.push([big[1], big.length].join());
                r.push(each(function (k) { return run(function () { ta[k] = { valueOf() { throw new Error('v'); } }; }); }));
                keys.forEach(untrap);
                count === keys.length ? r.join('|') : 'count ' + count;
                """);
    }

    @Test
    void getOwnPropertyDescriptor() {
        // Upstream knew no elements.
        assertWithAllModes_ES6(
                "2,true,true,true|2,true,true,true|2,true,true,true|2n|"
                        + allTrue()
                        + "|undefined|true,false",
                SETUP
                        + """
                function d(o, k) {
                  var v = Object.getOwnPropertyDescriptor(o, k);
                  return [v.value, v.writable, v.enumerable, v.configurable].join();
                }
                var ta = kinds[0];
                var r = [d(ta, '1'), d(ta, 1), d(kinds[1], '1')];
                r.push(Object.getOwnPropertyDescriptor(kinds[2], '1').value + 'n');
                r.push(each(function (k) { return Object.getOwnPropertyDescriptor(ta, k) === undefined; }));
                r.push(String(Reflect.getOwnPropertyDescriptor(ta, 'NaN')));
                r.push([Object.hasOwn(ta, '0'), Object.hasOwn(ta, 'NaN')].join());
                r.join('|');
                """);
    }

    @Test
    void defineOwnProperty() {
        // Upstream truncated "1.5" to the index 1, accepted "NaN", and reported false as success.
        assertWithAllModes_ES6(
                allFalse()
                        + "|1,2|"
                        + "TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError"
                        + "|1,2|true,9|true,5|false,false,false,false|"
                        + "TypeError,TypeError,TypeError,TypeError|1,5",
                SETUP
                        + """
                var ta = new Int8Array([1, 2]);
                var r = [each(function (k) { return Reflect.defineProperty(ta, k, { value: 9 }); })];
                r.push([ta[0], ta[1]].join());
                r.push(each(function (k) { return run(function () { Object.defineProperty(ta, k, { value: 9 }); }); }));
                r.push([ta[0], ta[1]].join());
                r.push([Reflect.defineProperty(ta, '1', { value: 9, writable: true, enumerable: true, configurable: true }), ta[1]].join());
                r.push([Reflect.defineProperty(ta, 1, { value: 5 }), ta[1]].join());
                var bad = [{ get() {} }, { configurable: false }, { enumerable: 0 }, { writable: false }];
                r.push(bad.map(function (d) { return Reflect.defineProperty(ta, '0', d); }).join());
                r.push(bad.map(function (d) { return run(function () { Object.defineProperty(ta, '0', d); }); }).join());
                r.push([ta[0], ta[1]].join());
                r.join('|');
                """);
    }

    @Test
    void defineOwnPropertyConversionErrorsGetThrough() {
        // Reflect.defineProperty turns a refusal into false, not the conversion of the value.
        assertWithAllModes_ES6(
                "TypeError|TypeError|Error|TypeError|true|9n",
                SETUP
                        + """
                var ta = new Int8Array(1), big = new BigInt64Array(1);
                var r = [
                  run(function () { return Reflect.defineProperty(big, '0', { value: 1 }); }),
                  run(function () { return Reflect.defineProperty(ta, '0', { value: Symbol() }); }),
                  run(function () { return Reflect.defineProperty(ta, '0', { value: { valueOf() { throw new Error('v'); } } }); }),
                  run(function () { return Reflect.defineProperty(ta, '0', { get() {}, value: 1 }); }),
                  Reflect.defineProperty(big, '0', { value: 9n }),
                  big[0] + 'n'
                ];
                r.join('|');
                """);
    }

    @Test
    void deleteInNonStrictCode() {
        // Upstream deleted "NaN" like an ordinary property.
        assertWithAllModes_ES6(
                allTrue() + "|" + allTrue() + "|false,false,false,false|false|1|true",
                SETUP
                        + """
                keys.forEach(function (k) { TA.prototype[k] = 1; });
                var ta = kinds[0];
                var r = [each(function (k) { return delete ta[k]; })];
                r.push(each(function (k) { return Reflect.deleteProperty(ta, k); }));
                r.push([delete ta[0], delete ta['0'], delete kinds[1][1], delete kinds[2]['1']].join());
                r.push(Reflect.deleteProperty(ta, '0'));
                r.push(ta[0]);
                r.push(TA.prototype['1.5'] === 1);
                keys.forEach(untrap);
                r.join('|');
                """);
    }

    @Test
    void deleteInStrictCode() {
        // A valid index is a TypeError with the operator, and false with Reflect.
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError|true,true|false,TypeError",
                SETUP
                        + """
                'use strict';
                var ta = kinds[0];
                var r = [[
                  run(function () { 'use strict'; delete ta[0]; }),
                  run(function () { 'use strict'; delete ta['1']; }),
                  run(function () { 'use strict'; delete kinds[2][0]; })
                ].join()];
                r.push([
                  (function () { 'use strict'; return delete ta['1.5']; })(),
                  (function () { 'use strict'; return delete ta.NaN; })()
                ].join());
                r.push([
                  (function () { 'use strict'; return Reflect.deleteProperty(ta, '0'); })(),
                  run(function () { 'use strict'; delete ta[1]; })
                ].join());
                r.join('|');
                """);
    }

    @Test
    void ownPropertyKeys() {
        // Upstream listed the elements or the other keys, depending on the entry point.
        assertWithAllModes_ES6(
                "0,1,foo,1.0,Symbol(s)|0,1,foo,1.0|0,1,foo,1.0|0,1,foo,1.0|0,1,foo,1.0|1,2,4,3|"
                        + "{\"0\":1,\"1\":2,\"foo\":4,\"1.0\":3}|0,1,foo,1.0",
                SETUP
                        + """
                var ta = new Int8Array([1, 2]);
                ta.foo = 4;
                ta['1.0'] = 3;
                ta[Symbol.for('s')] = 5;
                var forIn = [];
                for (var k in ta) forIn.push(k);
                var r = [Reflect.ownKeys(ta).map(String).join()];
                r.push(Object.getOwnPropertyNames(ta).join());
                r.push(Object.keys(ta).join());
                r.push(forIn.join());
                r.push(Object.keys(Object.getOwnPropertyDescriptors(ta)).join());
                r.push(Object.values(ta).join());
                r.push(JSON.stringify(ta));
                r.push(Object.keys(Object.assign({}, ta)).join());
                r.join('|');
                """);
    }

    @Test
    void ownPropertyKeysOfAViewOutOfBounds() {
        // An auto-length view whose offset is past the end has no elements either.
        assertWithAllModes_ES6(
                "0,1,foo|foo|foo",
                SETUP
                        + """
                var ab = new ArrayBuffer(4, { maxByteLength: 8 });
                var auto = new Int8Array(ab, 2), fixed = new Int8Array(ab, 2, 2);
                auto.foo = 1;
                fixed.foo = 1;
                var r = [Object.keys(auto).join()];
                ab.resize(1);
                r.push(Object.keys(auto).join(), Object.keys(fixed).join());
                r.join('|');
                """);
    }

    @Test
    void otherKeysAreOrdinaryProperties() {
        assertWithAllModes_ES6(
                "1|2|3|true|true|false|4|undefined",
                SETUP
                        + """
                var ta = new Int8Array(1);
                ta.foo = 1;
                ta['1.0'] = 2;
                ta['+1'] = 3;
                var r = [ta.foo, ta['1.0'], ta['+1'], 'foo' in ta, '1.0' in ta];
                r.push(delete ta.foo && 'foo' in ta);
                Object.defineProperty(ta, 'bar', { value: 4, configurable: true });
                r.push(ta.bar);
                r.push(String(Object.getOwnPropertyDescriptor(ta, 'none')));
                r.join('|');
                """);
    }

    @Test
    void integrityLevels() {
        // DefinePropertyOrThrow refuses to make an element non-configurable.
        assertWithAllModes_ES6(
                "TypeError|TypeError|false|ok|true|ok|true",
                SETUP
                        + """
                var empty = new Int8Array(0), sealed = new Int8Array(0);
                var r = [
                  run(function () { Object.freeze(new Int8Array(1)); }),
                  run(function () { Object.seal(new Int8Array(1)); }),
                  Object.isFrozen(Object.preventExtensions(new Int8Array(1))),
                  run(function () { Object.freeze(empty); return 'ok'; }),
                  Object.isFrozen(empty),
                  run(function () { Object.seal(sealed); return 'ok'; }),
                  Object.isSealed(sealed)
                ];
                r.join('|');
                """);
    }

    @Test
    void numbersDoNotConvertToBigInt() {
        // ToBigInt throws for a Number; the BigInt function still converts one.
        assertWithAllModes_ES6(
                "TypeError|TypeError|TypeError|TypeError|TypeError|3n|1n|2n",
                SETUP
                        + """
                var big = new BigInt64Array(1), ubig = new BigUint64Array(1);
                var r = [
                  run(function () { big[0] = 1; }),
                  run(function () { ubig[0] = 1; }),
                  run(function () { big.fill(1); }),
                  run(function () { new BigInt64Array([1]); }),
                  run(function () { big.set([1]); })
                ];
                big[0] = BigInt(3);
                r.push(big[0] + 'n');
                big[0] = true;
                r.push(big[0] + 'n');
                big[0] = '2';
                r.push(big[0] + 'n');
                r.join('|');
                """);
    }

    @Test
    void proxyOfATypedArray() {
        // Without traps a Proxy shows the internal methods of its target.
        assertWithAllModes_ES6(
                "0,1|1|false|false|false|true|false",
                SETUP
                        + """
                var ta = new Int8Array([1, 2]), p = new Proxy(ta, {});
                var r = [Reflect.ownKeys(p).join()];
                r.push(Object.getOwnPropertyDescriptor(p, '0').value);
                r.push('1.5' in p);
                r.push(Reflect.defineProperty(p, '1.5', { value: 1 }));
                r.push(Reflect.set({}, 'NaN', 1, p));
                r.push(Reflect.set({}, '0', 7, p) && ta[0] === 7);
                r.push(Reflect.deleteProperty(p, '0'));
                r.join('|');
                """);
    }
}
