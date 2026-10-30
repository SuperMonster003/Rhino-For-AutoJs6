/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (ROADMAP P6.19, appendix C Q46): ToBigInt in {@code BigInt.asIntN}, {@code
 * BigInt.asUintN} and the two setters of DataView, against the {@code BigInt} function that takes a
 * Number, and the null a {@code valueOf} or {@code toString} gives in OrdinaryToPrimitive, in both
 * modes. Not related to classes.
 */
class ToBigIntAndToPrimitiveTest {

    private static final String TO_BIGINT =
            """
            var dv = new DataView(new ArrayBuffer(16));
            var ops = {
              asIntN: function (v) { return BigInt.asIntN(64, v); },
              asUintN: function (v) { return BigInt.asUintN(64, v); },
              setBigInt64: function (v) { dv.setBigInt64(0, v); return dv.getBigInt64(0); },
              setBigUint64: function (v) { dv.setBigUint64(0, v); return dv.getBigUint64(0); },
            };
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            function each(v) {
              return Object.keys(ops).map(function (k) { return run(function () { return ops[k](v); }); }).join();
            }
            """;

    private static final String ERRORS = "TypeError,TypeError,TypeError,TypeError";

    private static final String OBJECTS =
            """
            var vn = { valueOf() { return null; } };
            var sn = { toString() { return null; } };
            var both = { valueOf() { return null; }, toString() { return 'S'; } };
            var str = { valueOf() { return 'V'; }, toString() { return null; } };
            function run(f) { try { return String(f()); } catch (e) { return e.name; } }
            """;

    @Test
    void numberIsATypeError() {
        // Upstream converted it, as the BigInt function does.
        assertWithAllModes_ES6(
                String.join("|", ERRORS, ERRORS, ERRORS, ERRORS, ERRORS, ERRORS),
                TO_BIGINT
                        + "[1, 0, -0, 1.5, NaN, Infinity].map(function (v) { return each(v); }).join('|');");
    }

    @Test
    void undefinedNullAndSymbolAreTypeErrors() {
        assertWithAllModes_ES6(
                String.join("|", ERRORS, ERRORS, ERRORS),
                TO_BIGINT + "[each(undefined), each(null), each(Symbol())].join('|');");
    }

    @Test
    void missingValueIsUndefined() {
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError",
                TO_BIGINT
                        + """
                [run(function () { return BigInt.asIntN(8); }),
                 run(function () { return BigInt.asUintN(8); }),
                 run(function () { return dv.setBigInt64(0); }),
                 run(function () { return dv.setBigUint64(0); })].join();
                """);
    }

    @Test
    void booleanAndBigInt() {
        assertWithAllModes_ES6(
                "1,1,1,1|0,0,0,0|5,5,5,5|-1,18446744073709551615,-1,18446744073709551615",
                TO_BIGINT + "[each(true), each(false), each(5n), each(-1n)].join('|');");
    }

    @Test
    void stringIsParsed() {
        assertWithAllModes_ES6(
                "12,12,12,12|16,16,16,16|0,0,0,0|7,7,7,7",
                TO_BIGINT + "[each('12'), each('0x10'), each(''), each('  7  ')].join('|');");
    }

    @Test
    void stringThatDoesNotParseIsASyntaxError() {
        String errors = "SyntaxError,SyntaxError,SyntaxError,SyntaxError";
        assertWithAllModes_ES6(
                String.join("|", errors, errors, errors),
                TO_BIGINT + "[each('1x'), each('1.5'), each('1n')].join('|');");
    }

    @Test
    void objectsGoThroughToPrimitiveWithTheNumberHint() {
        assertWithAllModes_ES6(
                String.join("|", ERRORS, ERRORS, ERRORS, ERRORS)
                        + "|3,3,3,3|3,3,3,3|number,number,number,number",
                TO_BIGINT
                        + """
                var hints = [];
                var hinted = { [Symbol.toPrimitive](h) { hints.push(h); return 1n; } };
                each(hinted);
                [each(Object(1)),
                 each({ valueOf() { return 1; } }),
                 each({ toString() { return 1; } }),
                 each({ [Symbol.toPrimitive]() { return 1; } }),
                 each(Object(3n)),
                 each({ valueOf() { return 3n; } }),
                 hints.join()].join('|');
                """);
    }

    @Test
    void orderOfTheConversions() {
        // ToIndex, then ToBigInt, and only then the bounds of the view.
        assertWithAllModes_ES6(
                "bits,value|index,value|TypeError|RangeError|RangeError",
                TO_BIGINT
                        + """
                var log = [];
                var bits = { valueOf() { log.push('bits'); return 8; } };
                var index = { valueOf() { log.push('index'); return 0; } };
                var value = { valueOf() { log.push('value'); return 1n; } };
                BigInt.asIntN(bits, value);
                var first = log.join();
                log.length = 0;
                dv.setBigInt64(index, value);
                [first, log.join(),
                 run(function () { dv.setBigInt64(100, 1); }),
                 run(function () { dv.setBigInt64(100, 1n); }),
                 run(function () { dv.setBigInt64(-1, 1); })].join('|');
                """);
    }

    @Test
    void bigIntFunctionStillTakesANumber() {
        assertWithAllModes_ES6(
                "1,0,0,1000000000000000000000,1,2,1,3,4|RangeError,RangeError,RangeError"
                        + "|TypeError,TypeError,TypeError|SyntaxError",
                TO_BIGINT
                        + """
                function all(list) {
                  return list.map(function (v) { return run(function () { return BigInt(v); }); }).join();
                }
                [all([1, 0, -0, 1e21, Object(1), { valueOf() { return 2; } }, true, '3', 4n]),
                 all([1.5, NaN, Infinity]),
                 all([undefined, null, Symbol()]),
                 all(['1x'])].join('|');
                """);
    }

    @Test
    void typedArraysStillThrowForANumber() {
        assertWithAllModes_ES6(
                "TypeError,TypeError,0,2",
                TO_BIGINT
                        + """
                var signed = new BigInt64Array(1);
                var unsigned = new BigUint64Array(1);
                [run(function () { signed[0] = 1; }),
                 run(function () { unsigned[0] = 1; }),
                 String(signed[0]),
                 run(function () { signed[0] = 2n; return signed[0]; })].join();
                """);
    }

    @Test
    void plusTakesTheNullOfValueOf() {
        // Upstream went on to toString and got "[object Object]".
        assertWithAllModes_ES6(
                "1|1|null|null|0|null",
                OBJECTS + "[vn + 1, 1 + vn, vn + '', '' + vn, vn + vn, both + ''].join('|');");
    }

    @Test
    void stringConcatenation() {
        assertWithAllModes_ES6(
                "anullb|xnull|nullV",
                OBJECTS
                        + """
                var s = 'x';
                s += vn;
                var a = 'a';
                [a + vn + 'b', s, sn + '' + str].join('|');
                """);
    }

    @Test
    void comparisons() {
        assertWithAllModes_ES6(
                "true,true,true,false,true,false,true,false",
                OBJECTS
                        + "[vn < 1, vn <= 0, vn >= 0, vn > 0, 0 >= vn, vn < 'a', vn < 1n, 1n < vn].join();");
    }

    @Test
    void looseEquality() {
        assertWithAllModes_ES6(
                "false,false,false,false,false,true",
                OBJECTS
                        + "[vn == null, vn == 0, vn == 'null', 1n == vn, 0n == vn, vn == vn].join();");
    }

    @Test
    void numberConversions() {
        assertWithAllModes_ES6(
                "0,0,true,0,1,false,0,0",
                OBJECTS
                        + "[Number(vn), +vn, Object.is(-vn, -0), vn * 2, vn | 1, isNaN(vn), Math.max(vn, -1), Number(both)].join();");
    }

    @Test
    void stringConversionsAskToStringFirst() {
        assertWithAllModes_ES6(
                "[object Object],[object Object],S,S,null,null,null,null",
                OBJECTS
                        + "[String(vn), `${vn}`, String(both), `${both}`, String(sn), `${sn}`, String(str), [sn].join()].join();");
    }

    @Test
    void toStringMayGiveNullAsWell() {
        // Upstream threw a TypeError when the other method gave no primitive.
        assertWithAllModes_ES6(
                "null,1,0,NaN,V,NaN",
                OBJECTS
                        + "[sn + '', sn + 1, Number(sn), parseInt(sn), str + '', Number(str)].join();");
    }

    @Test
    void propertyKeys() {
        assertWithAllModes_ES6(
                "null,null,7,constructor|null,true",
                OBJECTS
                        + """
                var o = {};
                o[sn] = 1;
                var C = class { [sn]() {} };
                [Object.keys(o)[0],
                 Object.keys(Object.fromEntries([[sn, 1]]))[0],
                 Reflect.get({ 'null': 7 }, sn),
                 Object.getOwnPropertyNames(C.prototype).join('|'),
                 sn in { 'null': 1 }].join();
                """);
    }

    @Test
    void toIndexAndBigInt() {
        assertWithAllModes_ES6(
                "0,0,0,a|TypeError,TypeError,TypeError",
                OBJECTS
                        + """
                [[BigInt.asIntN(sn, 1n), BigInt.asUintN(vn, 1n), new ArrayBuffer(sn).byteLength, 'abc'.charAt(vn)].join(),
                 [run(function () { return BigInt(vn); }),
                  run(function () { return BigInt(sn); }),
                  run(function () { return BigInt.asIntN(8, vn); })].join()].join('|');
                """);
    }

    @Test
    void symbolToPrimitiveIsNotAffected() {
        assertWithAllModes_ES6(
                "null,0,string,TypeError,5",
                OBJECTS
                        + """
                var tp = { [Symbol.toPrimitive]() { return null; }, valueOf() { return 1; } };
                var hint = { [Symbol.toPrimitive](h) { return h; } };
                var bad = { [Symbol.toPrimitive]() { return {}; } };
                var num = { [Symbol.toPrimitive]() { return 5; }, valueOf() { return null; } };
                [tp + '', Number(tp), `${hint}`, run(function () { return bad + ''; }), num + 0].join();
                """);
    }

    @Test
    void dateHints() {
        assertWithAllModes_ES6(
                "null,0,true,null,TypeError,0,0,1970-01-01T00:00:00.005Z",
                OBJECTS
                        + """
                var byString = new Date(5);
                byString.toString = function () { return null; };
                var byNumber = new Date(5);
                byNumber.valueOf = function () { return null; };
                [byString + '',
                 +byNumber,
                 byNumber + '' === Date.prototype.toString.call(byNumber),
                 String(Date.prototype[Symbol.toPrimitive].call(vn, 'number')),
                 run(function () { return Date.prototype[Symbol.toPrimitive].call(vn, 'nope'); }),
                 new Date(vn).getTime(),
                 new Date(sn).getTime(),
                 byNumber.toJSON()].join();
                """);
    }

    @Test
    void theOtherResultsAreUnchanged() {
        // Only the call of a method gives the null: a property that is null is no method.
        assertWithAllModes_ES6(
                "undefined,TypeError,[object Object],TypeError,TypeError,[object Object],1",
                OBJECTS
                        + """
                [{ valueOf() { return undefined; } } + '',
                 run(function () { return { valueOf() { return {}; }, toString() { return {}; } } + ''; }),
                 { valueOf: null } + '',
                 run(function () { return { valueOf: null, toString: null } + ''; }),
                 run(function () { return Object.create(null) + ''; }),
                 { valueOf: 1 } + '',
                 { valueOf() { return 1; } } + ''].join();
                """);
    }

    @Test
    void anObjectIsStillNoFunction() {
        assertWithAllModes_ES6(
                "TypeError,TypeError",
                OBJECTS
                        + """
                [run(function () { return Function.prototype.toString.call(vn); }),
                 run(function () { return Function.prototype.call.call(vn); })].join();
                """);
    }
}
