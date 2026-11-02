/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;
import static org.mozilla.javascript.testutils.Utils.assertWithAllModes_ES6;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.VarScope;

/**
 * AutoJs6 (appendix C Q55): an optional chain short circuits as a whole, and only a {@code ?.}
 * tests its value, in the interpreted and the compiled mode.
 */
class OptionalChainingTest {

    private static final String HELPERS =
            """
            function error(f) { try { f(); return 'no error'; } catch (e) { return e.name; } }
            function syntax(s) {
                try { new Function(s); return 'ok'; } catch (e) { return e.name; }
            }
            var calls = 0;
            function count() { calls++; return 'x'; }
            """;

    @Test
    void theRestOfTheChainIsNotEvaluated() {
        assertWithAllModes_ES6(
                "undefined,undefined,undefined,undefined,undefined,undefined,undefined|0",
                HELPERS
                        + """
                var u, n = null;
                var r = [
                    u?.a.b,
                    n?.a.b.c,
                    u?.a[count()],
                    u?.[count()].a,
                    u?.a(count()),
                    u?.a.b(count()).c[count()],
                    u?.(count()).a[count()]
                ];
                r.map(String).join() + '|' + calls;
                """);
    }

    @Test
    void onlyTheOptionalLinkTestsItsValue() {
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError",
                HELPERS
                        + """
                var o = { a: undefined, n: null, e: {} };
                [
                    error(() => o?.a.b),
                    error(() => o?.n.b),
                    error(() => o?.a[0]),
                    error(() => o?.a()),
                    error(() => o?.e.m()),
                    error(() => o?.e.m().x),
                    error(() => o?.a.b.c),
                ].join();
                """);
    }

    @Test
    void eachLinkShortCircuitsTheRest() {
        assertWithAllModes_ES6(
                "undefined,undefined,1,undefined,undefined,2,undefined|0",
                HELPERS
                        + """
                var o = { a: { b: { c: 1 } }, f() { return { g: 2 }; } };
                var r = [
                    o?.x?.y.z,
                    o?.a?.x?.y.z[count()],
                    o?.a?.b?.c,
                    o.x?.(count()).y,
                    o.a?.x?.y(count()),
                    o.f?.()?.g,
                    o.f?.().h?.i.j,
                ];
                r.map(String).join() + '|' + calls;
                """);
    }

    @Test
    void valuesThatAreNotNullish() {
        assertWithAllModes_ES6(
                "1,0,,false,1.0",
                """
                var o = { zero: 0, empty: '', no: false };
                var n = 1;
                var d = 0.5;
                [
                    'abc'?.length - 2, o.zero?.toFixed(), o.empty?.trim(), o.no?.valueOf(),
                    (d + d)?.toFixed(1)
                ].join();
                """);
    }

    @Test
    void numberVariablesInTheCompiledMode() {
        assertWithAllModes_ES6(
                "1.50|3|2.0",
                """
                function f(x) { var n = x * 1.5; return n?.toFixed(2); }
                function g() { var s = 0; for (var i = 0; i < 3; i++) { s += i?.valueOf(); } return s; }
                function h(a) { var n = 2; return a?.[n]?.toFixed?.(1); }
                [f(1), g(), h([0, 1, 2])].join('|');
                """);
    }

    @Test
    void callsKeepTheThisValue() {
        assertWithAllModes_ES6(
                "o,o,o,o,o,o,o,o,o,in,in,in,o,o",
                """
                var o = {
                    id: 'o',
                    m() { return this.id; },
                    in: { id: 'in', m() { return this.id; } }
                };
                var k = 'm';
                [
                    o?.m(), o?.[k](), o.m?.(), o[k]?.(), o?.m?.(), o?.[k]?.(),
                    (o?.m)(), (o.m)?.(), (o?.m)?.(),
                    o?.in.m(), (o?.in.m)(), (o?.in)?.m?.(),
                    (((o?.m)))(), (o?.[k])(),
                ].join();
                """);
    }

    @Test
    void parenthesesEndTheChain() {
        assertWithAllModes_ES6(
                "TypeError,TypeError,TypeError,TypeError,1,undefined",
                HELPERS
                        + """
                var u;
                [
                    error(() => (u?.a).b),
                    error(() => (u?.a)[0]),
                    error(() => (u?.a)()),
                    error(() => (u?.a.b)(count())),
                    calls,
                    (u?.a)?.b.c,
                ].map(String).join();
                """);
    }

    @Test
    void theArgumentsOfAChainInParenthesesRunBeforeTheError() {
        assertWithAllModes_ES6(
                "TypeError|in order",
                HELPERS
                        + """
                var u, seen = 'not evaluated';
                var r = error(() => (u?.a)((seen = 'in order')));
                r + '|' + seen;
                """);
    }

    @Test
    void deleteOfAChain() {
        assertWithAllModes_ES6(
                "true,true,true,true,true,true,true,false,false,false,true|0",
                HELPERS
                        + """
                var u, o = { a: { b: 1 }, c: 1, d: 2, e: { f: 3 }, g() { return 1; } };
                var r = [
                    delete u?.a,
                    delete u?.a.b,
                    delete u?.[count()],
                    delete (u?.a.b),
                    delete o?.a.b,
                    delete o?.['c'],
                    delete (o?.d),
                ];
                r.push('b' in o.a, 'c' in o, 'd' in o, delete o?.g());
                r.join() + '|' + calls;
                """);
    }

    @Test
    void earlyErrors() {
        assertWithAllModes_ES6(
                "SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,"
                        + "SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,"
                        + "SyntaxError,SyntaxError",
                HELPERS
                        + """
                [
                    'a?.b`x`', 'a?.b.c`x`', 'a?.()`x`', 'a?.[0]`x`', 'a?.b\\n`x`',
                    'new a?.b()', 'new a?.()', 'new a.b?.c',
                    'a?.b = 1', 'a?.b[c] = 1', 'a?.b.c++', '--a?.[b]', '[a?.b[c]] = []',
                ].map(syntax).join();
                """);
    }

    @Test
    void whatTheEarlyErrorsLeaveAlone() {
        assertWithAllModes_ES6(
                "ok,ok,ok,ok,ok,ok,ok",
                HELPERS
                        + """
                [
                    '(a?.b)`x`', 'new (a?.b)()', 'new a()?.b', 'new a?.5', 'x = a?.b',
                    'a?.5:1', 'class C extends (a?.b) {}',
                ].map(s => s == 'new a?.5' ? 'a ?.5 : 1' : s).map(syntax).join();
                """);
    }

    @Test
    void taggedTemplateOnAChainInParenthesesKeepsTheThisValue() {
        assertWithAllModes_ES6(
                "o,TypeError",
                HELPERS
                        + """
                var u, o = { id: 'o', t() { return this.id; } };
                [(o?.t)`x`, error(() => (u?.t)`x`)].join();
                """);
    }

    @Test
    void newOfAChainInParentheses() {
        assertWithAllModes_ES6(
                "1,TypeError",
                HELPERS
                        + """
                var u, o = { F: function () { this.x = 1; } };
                [new (o?.F)().x, error(() => new (u?.F)())].join();
                """);
    }

    @Test
    void optionalCallLooksTheCalleeUpLikeACall() {
        assertWithAllModes_ES6(
                "ReferenceError,TypeError,TypeError,TypeError,undefined,undefined,undefined|0",
                HELPERS
                        + """
                var u, o = { n: null, x: 1 };
                var r = [
                    error(() => missing?.()),
                    error(() => u.a?.()),
                    error(() => u[0]?.()),
                    error(() => o.x?.(count())),
                ];
                calls = 0;
                r.push(o.missing?.(count()), o['missing']?.(count()), o.n?.(count()));
                r.map(String).join() + '|' + calls;
                """);
    }

    @Test
    void theErrorNamesTheCallee() {
        assertWithAllModes_ES6(
                "true",
                """
                var o = { foo: 1 }, message;
                try { o.foo?.(); } catch (e) { message = e.message; }
                String(/foo/.test(message));
                """);
    }

    @Test
    void optionalCallOfEvalIsIndirect() {
        assertWithAllModes_ES6(
                "global,global,local",
                """
                var a = 'global';
                function f() { var a = 'local'; return eval?.('a'); }
                function g() { var a = 'local'; return (0, eval)?.('a'); }
                function h() { var a = 'local'; return eval('a'); }
                [f(), g(), h()].join();
                """);
    }

    @Test
    void optionalCallOfANameInAWithStatement() {
        assertWithAllModes_ES6(
                "o,o",
                """
                var o = { id: 'o', m() { return this.id; } }, r = [];
                with (o) { r.push(m?.(), m?.call(o)); }
                r.join();
                """);
    }

    @Test
    void superAndPrivateNames() {
        assertWithAllModes_ES6(
                "d|undefined|1|d|undefined|TypeError|2|undefined|TypeError",
                HELPERS
                        + """
                class B { m() { return this.id; } }
                class D extends B {
                    #x = 1; #n; #f() { return this.id; } #o = { y: 2 };
                    id = 'd';
                    sup() { return super.m?.(); }
                    missing() { return super.z?.().y; }
                    priv(o) { return o?.#x; }
                    meth(o) { return o?.#f(); }
                    opt(o) { return o.#n?.(); }
                    strict(o) { return o?.#n.y; }
                    deep(o) { return o?.#o.y; }
                }
                var d = new D(), u;
                [
                    d.sup(), d.missing(), d.priv(d), d.meth(d), d.opt(d),
                    error(() => d.strict(d)), d.deep(d), d.deep(u), error(() => d.deep({})),
                ].map(String).join('|');
                """);
    }

    @Test
    void spreadArguments() {
        assertWithAllModes_ES6(
                "1,2|1,2|o|undefined|undefined|0",
                HELPERS
                        + """
                var args = [1, 2], u;
                var o = { id: 'o', m(...r) { return r.join(); }, t() { return this.id; } };
                var f = (...r) => r.join();
                [
                    o?.m(...args), f?.(...args), o.t?.(...[]), u?.m(...[count()]),
                    u?.(...[count()]),
                ].map(String).join('|') + '|' + calls;
                """);
    }

    @Test
    void yieldInsideAChain() {
        assertWithAllModes_ES6(
                "a,b|o,arg|undefined|1,x",
                """
                function* g(o) { var r = o?.[yield 1].y; yield r; }
                function* h(o) { var r = o?.m(yield 'k'); yield r; }
                function* i(f) { var r = f?.(yield 'k'); yield r; }
                var a = g({ k: { y: 'a' } }); a.next(); var r1 = a.next('k').value;
                var u = g(undefined); var r2 = u.next().value;
                var b = h({ id: 'o', m(v) { return [this.id, v].join(); } });
                b.next();
                var r3 = b.next('arg').value;
                var c = h(undefined); var r4 = c.next().value;
                var d = i(v => [1, v].join()); d.next();
                var r5 = d.next('x').value;
                [[r1, 'b'].join(), r3, String(r4), r5].join('|');
                """);
    }

    @Test
    void theHeadOfAChainMayYield() {
        assertWithAllModes_ES6(
                "2|undefined",
                """
                function* g() { yield (yield 1)?.x.y; }
                function run(v) { var i = g(); i.next(); return i.next(v).value; }
                [run({ x: { y: 2 } }), String(run(null))].join('|');
                """);
    }

    @Test
    void awaitInsideAChain() {
        assertLog(
                "11|33|55||o",
                """
                async function f() {
                    log.push(await { a: [11] }?.a[0]);
                    var b = { c: [22, 33] };
                    log.push(b?.c[await Promise.resolve(1)]);
                    function e(v) { return v; }
                    log.push({ d: e }?.d(await Promise.resolve([44, 55]))[1]);
                    log.push(undefined?.arr[await Promise.reject(new Error('unreachable'))]);
                    var o = { id: 'o', m() { return this.id; } };
                    log.push(await o.m?.(await 0));
                }
                f();
                """);
    }

    @Test
    void chainsInsideChains() {
        assertWithAllModes_ES6(
                "2,3,undefined,undefined,5",
                """
                var u;
                var o = { a: { b: 2 }, f: v => v, k: 'b', g: { h: { i: 5 } } };
                [
                    o?.f(o?.a.b),
                    o?.f?.(o?.f?.(3)),
                    o?.f(u?.a.b),
                    o?.[u?.a]?.b.c,
                    (o?.g?.h)?.i,
                ].map(String).join();
                """);
    }

    @Test
    void chainsInLoopsAndConditions() {
        assertWithAllModes_ES6(
                "undefined,1,undefined,3|yes|d|t|undefined|x",
                """
                var list = [null, { v: { w: 1 } }, undefined, { v: { w: 3 } }], out = [];
                for (var i = 0; i < list.length; i++) { out.push(list[i]?.v.w); }
                var u, o = { a: 1 };
                function def(p, q = p?.a.b) { return q; }
                [
                    out.map(String).join(),
                    o?.a ? 'yes' : 'no',
                    u?.a.b ?? 'd',
                    typeof u?.a.b === 'undefined' ? 't' : 'f',
                    String(def(undefined)),
                    `${u?.a.b ?? 'x'}`,
                ].join('|');
                """);
    }

    @Test
    void manyLinks() {
        assertWithAllModes_ES6(
                "1|undefined",
                """
                var o = { v: 1 };
                o.n = o;
                var src = 'x';
                for (var i = 0; i < 40; i++) { src += '?.n'; }
                var f = new Function('x', 'return ' + src + '?.v');
                [f(o), String(f(null))].join('|');
                """);
    }

    @Test
    void functionsInTheRestOfAChain() {
        assertWithAllModes_ES6(
                "3|undefined",
                """
                var o = { map(f) { return f(1); } }, u;
                var y = 2;
                [o?.map(function (x) { return x + y; }), String(u?.map(x => x + y))].join('|');
                """);
    }

    /** The call of {@code f?.()} stays an inline call of the interpreter. */
    @Test
    void continuationsCrossAnOptionalCall() {
        try (Context cx = Context.enter()) {
            cx.setInterpretedMode(true);
            cx.setLanguageVersion(Context.VERSION_ES6);
            TopLevel scope = cx.initStandardObjects();
            SerializableCallable capture =
                    (c, s, thisObj, args) -> {
                        throw c.captureContinuation();
                    };
            ScriptableObject.putProperty(
                    scope.getGlobalThis(),
                    "capture",
                    new LambdaFunction(scope, "capture", 0, capture));
            Script script =
                    cx.compileString(
                            """
                            function f() { return capture(); }
                            var o = { f: f };
                            f?.() + o.f?.() + o?.f();
                            """,
                            "test",
                            1,
                            null);
            ContinuationPending pending =
                    assertThrows(
                            ContinuationPending.class,
                            () -> cx.executeScriptWithContinuations(script, scope));
            pending = assertThrows(ContinuationPending.class, cont(cx, scope, pending, "a"));
            pending = assertThrows(ContinuationPending.class, cont(cx, scope, pending, "b"));
            Object result = cx.resumeContinuation(pending.getContinuation(), scope, "c");
            assertEquals("abc", Context.toString(result));
        }
    }

    private static Executable cont(
            Context cx, VarScope scope, ContinuationPending pending, Object value) {
        return () -> cx.resumeContinuation(pending.getContinuation(), scope, value);
    }
}
