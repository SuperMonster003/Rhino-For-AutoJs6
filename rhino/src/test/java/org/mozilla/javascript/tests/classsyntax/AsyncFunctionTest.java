/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.AsyncTracker;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.testutils.Utils;

/**
 * AutoJs6 async (ROADMAP P9.1): async functions, methods and arrow functions, the interfaces that
 * the host uses (Q6, Q7), and the upstream bugs that the async tests found.
 */
class AsyncFunctionTest {

    @Test
    void bodyRunsUntilTheFirstAwaitAndTheRestInAMicrotask() {
        assertLog(
                "a|b|c|d|7",
                """
                async function f() { log.push('a'); await null; log.push('c'); return 7; }
                var p = f();
                log.push('b');
                Promise.resolve().then(function () { log.push('d'); });
                p.then(function (v) { log.push(v); });
                """);
    }

    @Test
    void awaitOfANativePromiseTakesOneTick() {
        assertLog(
                "await|1|2|3",
                """
                var p = Promise.resolve();
                (async function () { await p; log.push('await'); })();
                p.then(function () { log.push(1); }).then(function () { log.push(2); })
                    .then(function () { log.push(3); });
                """);
    }

    /** Appendix C Q56 and Q57: only a plain function gets the hoisting of Annex B. */
    @Test
    void blockDeclarationsAreBlockScoped() {
        assertLog(
                "undefined|undefined|undefined|function"
                        + "|undefined|undefined|undefined|function"
                        + "|function|function|function"
                        + "|1|1|1"
                        + "|undefined|undefined",
                """
                function names() {
                    { async function a() {} function* b() {} async function* c() {} function d() {} }
                    log.push(typeof a, typeof b, typeof c, typeof d);
                }
                names();
                switch (0) {
                    default:
                        async function e() {}
                        function* f() {}
                        async function* g() {}
                        function h() {}
                }
                log.push(typeof e, typeof f, typeof g, typeof h);
                function inside() {
                    {
                        log.push(typeof a, typeof b, typeof c);
                        async function a() {} function* b() {} async function* c() {}
                    }
                }
                inside();
                function outer() {
                    var a = 1, b = 1, c = 1;
                    { async function a() {} function* b() {} async function* c() {} }
                    log.push(a, b, c);
                }
                outer();
                log.push(eval('{ async function i() {} } typeof i'));
                (function () { 'use strict'; { function* j() {} } log.push(typeof j); })();
                """);
    }

    @Test
    void blockDeclarationsMayStillNotBeRedeclared() {
        assertLog(
                "SyntaxError|SyntaxError|SyntaxError|SyntaxError",
                """
                function syntax(s) { try { new Function(s); return 'ok'; } catch (e) { return e.name; } }
                log.push(
                    syntax('{ async function f() {} var f; }'),
                    syntax('{ var f; function* f() {} }'),
                    syntax('{ async function f() {} async function f() {} }'),
                    syntax('{ function f() {} function* f() {} }'));
                """);
    }

    @Test
    void returnsANativePromise() {
        assertLog(
                "true|true|[object AsyncFunction]",
                """
                async function f() {}
                log.push(f() instanceof Promise);
                log.push(Object.getPrototypeOf(f) !== Function.prototype);
                log.push(Object.prototype.toString.call(f));
                """);
    }

    @Test
    void anExceptionRejects() {
        assertLog(
                "before|boom|after await",
                """
                async function f() { throw 'boom'; }
                async function g() { await null; log.push('after await'); throw 'x'; }
                var p = f();
                log.push('before');
                p.catch(function (e) { log.push(e); });
                g().catch(function () {});
                """);
    }

    @Test
    void aRejectedAwaitThrowsAtTheAwait() {
        assertLog(
                "caught r|finally|done",
                """
                async function f() {
                  try { await Promise.reject('r'); log.push('no'); }
                  catch (e) { log.push('caught ' + e); }
                  finally { await null; log.push('finally'); }
                  return 'done';
                }
                f().then(function (v) { log.push(v); });
                """);
    }

    @Test
    void awaitResolvesThenables() {
        assertLog(
                "1|2|3|r",
                """
                var then = { then: function (ok) { ok(2); } };
                var bad = { then: function (ok, no) { no('r'); } };
                async function f() {
                  log.push(await 1);
                  log.push(await then);
                  log.push(await Promise.resolve(3));
                  try { await bad; } catch (e) { log.push(e); }
                }
                f();
                """);
    }

    @Test
    void aThrowingThenGetterRejectsTheAwait() {
        assertLog(
                "getter",
                """
                var o = { get then() { throw 'getter'; } };
                (async function () { await o; })().catch(function (e) { log.push(e); });
                """);
    }

    @Test
    void aJavaFutureIsNotAThenable() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    TopLevel scope = cx.initStandardObjects();
                    CompletableFuture<String> future = new CompletableFuture<>();
                    ScriptableObject.putProperty(scope, "future", Context.javaToJS(future, scope));
                    cx.evaluateString(
                            scope,
                            "var same; (async function () { same = (await future) === future; })()",
                            "test.js",
                            1,
                            null);
                    cx.processMicrotasks();
                    assertEquals(Boolean.TRUE, ScriptableObject.getProperty(scope, "same"));
                    return null;
                });
    }

    @Test
    void arrowFunctionsKeepThisAndArguments() {
        assertLog(
                "o|1,2|3",
                """
                var o = {
                  name: 'o',
                  m: function () {
                    return (async () => {
                      await null;
                      log.push(this.name);
                      log.push([].join.call(arguments));
                    })();
                  }
                };
                o.m(1, 2);
                var add = async (a, b = 2) => a + b;
                add(1).then(function (v) { log.push(v); });
                """);
    }

    @Test
    void methodsOfClassesAndObjects() {
        assertLog(
                "base derived|static|literal 1|false|false",
                """
                class A { async m() { await null; return 'base'; } }
                class B extends A {
                  async m() { return (await super.m()) + ' derived'; }
                  static async s() { return 'static'; }
                }
                var o = { x: 1, async m() { await null; return 'literal ' + this.x; } };
                (async function () {
                  log.push(await new B().m());
                  log.push(await B.s());
                  log.push(await o.m());
                  log.push(o.m.hasOwnProperty('prototype'));
                  log.push(B.prototype.m.hasOwnProperty('prototype'));
                })();
                """);
    }

    @Test
    void privateAndComputedAsyncMethods() {
        assertLog(
                "private 1|computed",
                """
                class C {
                  #x = 1;
                  async #p() { await null; return 'private ' + this.#x; }
                  run() { return this.#p(); }
                  async ['com' + 'puted']() { return 'computed'; }
                }
                new C().run().then(function (v) {
                  log.push(v);
                  new C().computed().then(function (w) { log.push(w); });
                });
                """);
    }

    @Test
    void asyncFunctionsAreNotConstructors() {
        assertLog(
                "TypeError|TypeError|false",
                """
                async function f() {}
                try { new f(); } catch (e) { log.push(e.name); }
                try { new (async () => {})(); } catch (e) { log.push(e.name); }
                log.push(f.hasOwnProperty('prototype'));
                """);
    }

    @Test
    void parameterErrorsReject() {
        assertLog(
                "rejected boom|rejected TypeError",
                """
                async function f(a = (function () { throw 'boom'; })()) {}
                async function g({ a }) {}
                f().catch(function (e) { log.push('rejected ' + e); });
                g().catch(function (e) { log.push('rejected ' + e.name); });
                """);
    }

    @Test
    void theNameOfAFunctionExpressionIsImmutable() {
        assertLog(
                "function|TypeError",
                """
                var a = async function B() { B = 1; log.push(typeof B); };
                var s = async function C() { 'use strict'; C = 1; };
                a();
                s().catch(function (e) { log.push(e.name); });
                """);
    }

    @Test
    void theConstructorIsReachedThroughThePrototype() {
        assertLog(
                "undefined|3|async function anonymous(a,b\n) {\nreturn a + b\n}",
                """
                var C = Object.getPrototypeOf(async function () {}).constructor;
                log.push(typeof globalThis.AsyncFunction);
                var f = new C('a', 'b', 'return a + b');
                f(1, 2).then(function (v) { log.push(v); log.push(f.toString()); });
                """);
    }

    @Test
    void stackAndPositionOfAnErrorAfterAnAwait() {
        Utils.runWithAllModes(
                cx -> {
                    String log =
                            AsyncScripts.runLog(
                                    cx,
                                    """
                                    async function f() {
                                      await null;
                                      undefinedName;
                                    }
                                    f().catch(function (e) {
                                      log.push(e.name, e.lineNumber);
                                      log.push(e.stack.indexOf('test.js:3') >= 0);
                                    });
                                    """);
                    assertEquals("ReferenceError|3|true", log);
                    return null;
                });
    }

    @Test
    void theTrackerNamesTheFirstPendingAwait() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    TopLevel scope = cx.initStandardObjects();
                    AsyncTracker tracker = AsyncTracker.of(cx);
                    assertEquals(0, tracker.pendingCount());
                    assertNull(tracker.firstPending());
                    cx.evaluateString(
                            scope,
                            """
                            var release;
                            var never = new Promise(function (ok) { release = ok; });
                            async function f() {
                              await never;
                            }
                            f();
                            (async function () { await never; })();
                            """,
                            "tracked.js",
                            1,
                            null);
                    cx.processMicrotasks();
                    assertEquals(2, tracker.pendingCount());
                    assertNotNull(tracker.firstPending());
                    assertEquals("tracked.js", tracker.firstPending().sourceName());
                    assertEquals(4, tracker.firstPending().lineNumber());
                    cx.evaluateString(scope, "release()", "release", 1, null);
                    cx.processMicrotasks();
                    assertEquals(0, tracker.pendingCount());
                    return null;
                });
    }

    @Test
    void aSettledAwaitIsNotPending() {
        Utils.runWithAllModes(
                cx -> {
                    AsyncScripts.runLog(cx, "(async function () { await 1; await 2; })();");
                    assertEquals(0, AsyncTracker.of(cx).pendingCount());
                    return null;
                });
    }

    @Test
    void unhandledRejectionsOfAContextAreTracked() {
        Utils.runWithAllModes(
                cx -> {
                    cx.setTrackUnhandledPromiseRejections(true);
                    AsyncScripts.runLog(
                            cx,
                            """
                            async function f() { await null; throw 'lost'; }
                            f();
                            (async function () { throw 'handled'; })().catch(function () {});
                            """);
                    List<Object> lost = new ArrayList<>();
                    cx.getUnhandledPromiseTracker().process(lost::add);
                    assertEquals(List.of("lost"), lost);
                    assertTrue(cx.getUnhandledPromiseTracker().enumerate().isEmpty());
                    return null;
                });
    }

    @Test
    void theUnhandledRejectionsOfAnotherContextAreSeparate() throws InterruptedException {
        try (Context first = Context.enter()) {
            first.setTrackUnhandledPromiseRejections(true);
            AsyncScripts.runLog(first, "Promise.reject('first');");
            assertEquals(1, first.getUnhandledPromiseTracker().enumerate().size());
            boolean[] empty = new boolean[1];
            Thread other =
                    new Thread(
                            () -> {
                                try (Context cx = Context.enter()) {
                                    cx.setTrackUnhandledPromiseRejections(true);
                                    empty[0] =
                                            cx.getUnhandledPromiseTracker().enumerate().isEmpty();
                                }
                            });
            other.start();
            other.join();
            assertTrue(empty[0]);
        }
    }

    @Test
    void theStrictnessOfAFunctionHoldsAfterAnAwait() {
        // A generator body runs in resumeGenerator, and took the strictness of its caller.
        assertLog(
                "TypeError|sloppy|TypeError",
                """
                var o = Object.freeze({ x: 1 });
                async function a() { 'use strict'; await null; o.x = 2; }
                a().catch(function (e) { log.push(e.name); });
                function* g() { 'use strict'; o.x = 2; }
                try { g().next(); log.push('no error'); } catch (e) { log.push(e.name); }
                function* h() { o.x = 2; log.push('sloppy'); }
                (function () { 'use strict'; h().next(); })();
                """);
    }

    @Test
    void nestedTryInAGeneratorKeepsTheOuterCatch() {
        // The compiler did not pop a try of a generator: the handlers of the enclosing try were
        // never added, whether the body is an async function or a plain generator.
        assertLog(
                "caught x|2|end|caught y|caught z",
                """
                function* g() {
                  try { try { yield 1; } catch (e2) { throw e2; } }
                  catch (e) { log.push('caught ' + e); yield 2; }
                  log.push('end');
                }
                var i = g(); i.next(); log.push(i.throw('x').value); i.next();
                async function f() {
                  try { try { await Promise.reject('y'); } finally { await null; } }
                  catch (e) { log.push('caught ' + e); }
                  try { try { await Promise.reject('z'); } catch (e2) { throw e2; } }
                  catch (e) { log.push('caught ' + e); }
                }
                f();
                """);
    }

    @Test
    void localsAcrossAnAwaitInLiteralsAndNew() {
        // Registers that stayed reserved after an object literal or a direct new held no value
        // of a known type where a generator saved its locals (VerifyError).
        assertLog(
                "5|6",
                """
                function K(v) { this.v = v; }
                async function f() {
                  var o = { __proto__: { p: 1 }, m() { return super.p; }, v: 5 };
                  await null;
                  log.push(o.v);
                  var k = new K(6);
                  try { throw k; } catch (e) { await null; log.push(e.v); }
                }
                f();
                """);
    }

    @Test
    void promiseCombinatorsGetResolveBeforeTheIterator() {
        assertLog(
                "TypeError|TypeError|TypeError|false",
                """
                var opened = false;
                var iterable = { [Symbol.iterator]() { opened = true; return [][Symbol.iterator](); } };
                class P extends Promise { static get resolve() { return 1; } }
                ['all', 'race', 'any'].forEach(function (m) {
                  P[m](iterable).catch(function (e) { log.push(e.name); });
                });
                Promise.resolve().then(function () {}).then(function () { log.push(opened); });
                """);
    }

    @Test
    void functionConstructorSourceFollowsTheSpecification() {
        assertLog(
                "function anonymous(a //\n) {\nreturn a\n}|1|function* anonymous(\n) {\n\n}",
                """
                var f = new Function('a //', 'return a');
                log.push(f.toString());
                log.push(f(1));
                var G = Object.getPrototypeOf(function* () {}).constructor;
                log.push(new G().toString());
                """);
    }

    @Test
    void functionPrototypeToStringOfACallableProxy() {
        assertLog(
                "function () {\n    [native code]\n}",
                "log.push(Function.prototype.toString.call(new Proxy(function () {}, {})));");
    }

    @Test
    void asyncIteratorMethodKeepsAPrimitiveThis() {
        assertLog(
                "true|true|true",
                """
                async function* g() {}
                var A = Object.getPrototypeOf(Object.getPrototypeOf(g.prototype));
                var f = A[Symbol.asyncIterator];
                var s = Symbol();
                log.push(f.call(s) === s);
                log.push(f.call(4) === 4);
                log.push(f.apply('x') === 'x');
                """);
    }
}
