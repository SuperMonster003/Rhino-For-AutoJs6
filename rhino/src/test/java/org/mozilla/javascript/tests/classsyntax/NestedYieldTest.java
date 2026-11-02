/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;

/**
 * AutoJs6 (appendix C Q58): a yield or an await inside the operand of another one runs where it is
 * written, after what comes before it and only when it is reached, in both modes. The compiled mode
 * used to run it before the whole outer expression.
 */
class NestedYieldTest {

    private static final String RUN =
            """
            var log = [];
            function t(v) { log.push('t' + v); return v; }
            function run(g) {
                var i = g(), n = 0, r = i.next();
                while (!r.done) { log.push('y:' + r.value); r = i.next('v' + ++n); }
                log.push('r:' + r.value);
                return log.join(' ');
            }
            """;

    private static void assertBody(String expected, String body) {
        assertWithAllModes_ES6(expected, RUN + "run(function* () { " + body + " });");
    }

    @Test
    void arrayLiteral() {
        assertBody("t1 y:i t3 y:1,v1,3 r:undefined", "yield [t(1), yield 'i', t(3)];");
        assertBody("t1 y:i t3 y:1,v,1,3 r:undefined", "yield [t(1), ...(yield 'i'), t(3)];");
    }

    @Test
    void conditionalOperator() {
        assertBody("y:2 r:undefined", "var c = false; yield c ? (yield 1) : 2;");
        assertBody("y:1 y:v1 r:undefined", "var c = true; yield c ? (yield 1) : 2;");
        assertBody("y:1 t2 y:2 r:undefined", "yield (yield 1) ? t(2) : t(3);");
    }

    @Test
    void objectLiteral() {
        assertBody(
                "t1 y:i t3 y:{\"a\":1,\"b\":\"v1\",\"c\":3} r:undefined",
                "yield JSON.stringify({ a: t(1), b: yield 'i', c: t(3) });");
    }

    @Test
    void arguments() {
        assertBody(
                "t1 y:i t3 y:1+v1+3 r:undefined",
                """
                function f(a, b, c) { return [a, b, c].join('+'); }
                yield f(t(1), yield 'i', t(3));
                """);
        assertBody(
                "t1 y:i t3 y:o:1+v1+3 r:undefined",
                """
                var o = { id: 'o', m(a, b, c) { return this.id + ':' + [a, b, c].join('+'); } };
                yield o.m(t(1), yield 'i', t(3));
                """);
        assertBody(
                "t1 y:i y:1v1 r:undefined",
                """
                function C(a, b) { this.s = a + b; }
                yield new C(t(1), yield 'i').s;
                """);
        assertBody(
                "t1 y:i t3 y:1+v1+3 r:undefined",
                """
                function f(...r) { return r.join('+'); }
                yield f(...[t(1)], yield 'i', ...[t(3)]);
                """);
    }

    @Test
    void logicalOperators() {
        assertBody("y:0 r:undefined", "var c = 0; yield c && (yield 'i');");
        assertBody("y:1 r:undefined", "var c = 1; yield c || (yield 'i');");
        assertBody("y:1 r:undefined", "var c = 1; yield c ?? (yield 'i');");
        assertBody("y:i y:v1 r:undefined", "var c = 1; yield c && (yield 'i');");
        assertBody("y:i y:v1 r:undefined", "var c = 0; yield c || (yield 'i');");
        assertBody("y:i y:v1 r:undefined", "var c = null; yield c ?? (yield 'i');");
    }

    @Test
    void logicalAssignment() {
        assertBody("y:i y:v1 r:v1", "var x = 0; yield (x ||= yield 'i'); return x;");
        assertBody("y:1 r:1", "var x = 1; yield (x ||= yield 'i'); return x;");
    }

    @Test
    void operators() {
        assertBody("t1 y:i t3 y:1v13 r:undefined", "yield t(1) + (yield 'i') + t(3);");
        assertBody("y:1 y:true r:undefined", "yield (yield 1) === 'v1';");
        assertBody("y:1 y:string r:undefined", "yield typeof (yield 1);");
        assertBody("y:1 y:false r:undefined", "yield !(yield 1);");
        assertBody("y:1 y:true r:undefined", "yield 'length' in Object(yield 1);");
    }

    @Test
    void numbers() {
        assertWithAllModes_ES6(
                "3,20,7",
                """
                function* g() { var i = 2; i += yield i * (yield i + 1); return i; }
                var it = g();
                [it.next().value, it.next(10).value, it.next(5).value].join();
                """);
    }

    @Test
    void chainsOfYields() {
        assertBody("y:1 y:v1 y:v2 r:v3", "return yield yield yield 1;");
        assertBody("y:1 y:v1 r:undefined", "yield yield 1;");
    }

    @Test
    void conditions() {
        assertBody("y:1 y:v1 t2 r:undefined", "if (yield (yield 1)) t(2);");
        assertBody("y:1 y:v1 t3 r:undefined", "switch (yield (yield 1)) { case 'v2': t(3); }");
        assertBody("y:1 y:v1 t2 r:undefined", "while (yield (yield 1)) { t(2); break; }");
    }

    @Test
    void members() {
        assertBody("y:a y:b y:x r:undefined", "yield ({ v1: { v2: 'x' } })[yield 'a'][yield 'b'];");
    }

    @Test
    void valuesBelowBothYieldsStayOnTheStack() {
        assertBody(
                "t0 t1 y:i t2 y:1,v1,2 t3 r:0,v2,3",
                "var a = [t(0), yield [t(1), yield 'i', t(2)], t(3)]; return a;");
    }

    @Test
    void templateLiteral() {
        assertBody(
                "t1 y:i y:<v1> t3 y:1-v2-3 r:undefined",
                "yield `${t(1)}-${yield `<${yield 'i'}>`}-${t(3)}`;");
    }

    @Test
    void delegation() {
        assertBody("t1 y:i y:1 y:v1 r:undefined", "yield* [t(1), yield 'i'];");
        assertBody(
                "y:a t1 y:b,1 r:v2",
                """
                function* inner() { yield 'a'; return 'b'; }
                return yield [yield* inner(), t(1)];
                """);
    }

    @Test
    void optionalChain() {
        assertBody(
                "y:k y:o,v1 r:undefined",
                """
                var o = { id: 'o', m(v) { return [this.id, v].join(); } };
                yield o?.m(yield 'k');
                """);
        assertBody("y:k y:a r:undefined", "var o = { v1: { y: 'a' } }; yield o?.[yield 'k'].y;");
        assertBody("y:undefined r:undefined", "var u; yield u?.[yield 'k'];");
    }

    @Test
    void classElements() {
        assertBody(
                "y:k y:m r:undefined",
                "yield (class { static [yield 'k']() { return 'm'; } }).v1();");
    }

    @Test
    void destructuringInTheOperand() {
        assertBody(
                "t0 t1 y:i y:1,v1 y:0+v2 r:undefined",
                """
                var a, b, f = (x, y) => x + '+' + y;
                yield f(t(0), yield ([a, b] = [t(1), yield 'i']));
                """);
        assertBody(
                "t0 closed y:it y:0,v1 r:undefined",
                """
                var it = {
                    [Symbol.iterator]() { return this; },
                    next() { return { value: 1, done: false }; },
                    return() { log.push('closed'); return {}; },
                    toString() { return 'it'; },
                };
                var a;
                yield [t(0), yield ([a] = it)];
                """);
    }

    @Test
    void yieldInTheInitializerOfATemporaryScope() {
        assertBody("t1 y:i r:1v1", "var a, b; [a, b] = [t(1), yield 'i']; return a + b;");
        assertBody("y:i r:v1", "var a; ({ a } = { a: yield 'i' }); return a;");
        assertBody("y:d r:v1", "var [a = yield 'd'] = []; return a;");
        assertBody("y:d r:v1", "let { a = yield 'd' } = {}; return a;");
        assertBody("y:i y:v1 r:v1", "var a; yield ([a] = [yield 'i']); return a;");
    }

    @Test
    void throwAndReturnAtTheInnerYield() {
        assertWithAllModes_ES6(
                "t1 c:x f",
                RUN
                        + """
                        function* g() {
                            try {
                                yield [t(1), yield 'i', t(3)];
                            } catch (e) {
                                log.push('c:' + e);
                            } finally {
                                log.push('f');
                            }
                        }
                        var i = g();
                        i.next();
                        i.throw('x');
                        log.join(' ');
                        """);
        assertWithAllModes_ES6(
                "t1 f r",
                RUN
                        + """
                        function* g() {
                            try {
                                yield [t(1), yield 'i', t(3)];
                            } finally {
                                log.push('f');
                            }
                        }
                        var i = g();
                        i.next();
                        log.push(i.return('r').value);
                        log.join(' ');
                        """);
    }

    private static final String TRACE = "function t(v) { log.push('t' + v); return v; }\n";

    private static void assertAsync(String expected, String body) {
        assertLog(expected, TRACE + "async function f() { " + body + " } f();");
    }

    @Test
    void awaitInAConditionalOperator() {
        assertAsync("r2", "var u = false; log.push('r' + await (u ? await t(1) : 2));");
        assertAsync("t1|r1", "var u = true; log.push('r' + await (u ? await t(1) : 2));");
    }

    @Test
    void awaitInLiteralsAndArguments() {
        assertAsync("t1|t2|t3|1,2,3", "log.push(await [t(1), await t(2), t(3)]);");
        assertAsync(
                "t1|t2|t3|{\"a\":1,\"b\":2,\"c\":3}",
                "log.push(JSON.stringify(await { a: t(1), b: await t(2), c: t(3) }));");
        assertAsync(
                "t1|t2|t3|t4|1,2,3,4",
                """
                function g(...r) { return r.join(); }
                log.push(await g(t(1), await t(2), await [t(3), await t(4)]));
                """);
    }

    @Test
    void awaitInAnOptionalChain() {
        assertAsync("undefined", "var u; log.push(String(await u?.[await t(1)]));");
        assertAsync("t1|x", "var o = { 1: 'x' }; log.push(await o?.[await t(1)]);");
    }

    @Test
    void awaitInLogicalOperators() {
        assertAsync("0", "log.push(await (0 && await t(1)));");
        assertAsync("t1|1", "log.push(await (1 && await t(1)));");
        assertAsync("2", "log.push(await (2 || await t(1)));");
        assertAsync("3", "log.push(await (3 ?? await t(1)));");
    }

    @Test
    void awaitInAnAsyncArrowFunction() {
        assertLog(
                "t1|1",
                TRACE
                        + """
                        var f = async () => await (await t(1));
                        f().then(v => log.push(v));
                        """);
    }

    @Test
    void asyncGenerator() {
        assertLog(
                "t1|t2|i|t3|1,2,a,3|b|true",
                TRACE
                        + """
                        async function* g() {
                            var v = yield [t(1), await t(2), yield 'i', t(3)];
                            log.push(v);
                        }
                        async function drive() {
                            var i = g();
                            log.push((await i.next()).value);
                            log.push((await i.next('a')).value);
                            log.push(String((await i.next('b')).done));
                        }
                        drive();
                        """);
    }
}
