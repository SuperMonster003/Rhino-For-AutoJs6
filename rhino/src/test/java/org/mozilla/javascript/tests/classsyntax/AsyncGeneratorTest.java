/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;

import org.junit.jupiter.api.Test;

/** AutoJs6 async (ROADMAP P9.3): async generators. */
class AsyncGeneratorTest {

    private static final String PUSH =
            """
            function push(r) { log.push(r.value + ':' + r.done); }
            function fail(e) { log.push('error ' + e); }
            """;

    @Test
    void nextAndAwaitInTheBody() {
        assertLog(
                "1:false|2:false|3:true|undefined:true",
                PUSH
                        + """
                        async function* g() { yield 1; yield await Promise.resolve(2); return 3; }
                        var i = g();
                        i.next().then(push);
                        i.next().then(push);
                        i.next().then(push);
                        i.next().then(push);
                        """);
    }

    @Test
    void returnsAnAsyncIterator() {
        assertLog(
                "true|[object AsyncGenerator]|false|TypeError",
                """
                async function* g() {}
                var i = g();
                log.push(i[Symbol.asyncIterator]() === i);
                log.push(Object.prototype.toString.call(i));
                log.push(i.hasOwnProperty('next'));
                try { new g(); } catch (e) { log.push(e.name); }
                """);
    }

    @Test
    void parameterErrorsThrowSynchronously() {
        assertLog(
                "boom",
                """
                async function* g(a = (function () { throw 'boom'; })()) {}
                try { g(); } catch (e) { log.push(e); }
                """);
    }

    @Test
    void yieldAwaitsItsOperand() {
        assertLog(
                "1:false|error r",
                PUSH
                        + """
                        async function* g() { yield Promise.resolve(1); yield Promise.reject('r'); }
                        var i = g();
                        i.next().then(push);
                        i.next().catch(fail);
                        """);
    }

    @Test
    void requestsAreQueued() {
        assertLog(
                "start|1:false|2:false|undefined:true",
                PUSH
                        + """
                        async function* g() {
                          log.push('start');
                          await null;
                          yield 1;
                          await null;
                          yield 2;
                        }
                        var i = g();
                        i.next().then(push);
                        i.next().then(push);
                        i.next().then(push);
                        """);
    }

    @Test
    void returnAwaitsItsValue() {
        assertLog(
                "7:true|8:true",
                PUSH
                        + """
                        async function* g() { yield 1; }
                        var i = g();
                        i.return(Promise.resolve(7)).then(push);
                        i.return(8).then(push);
                        """);
    }

    @Test
    void aReturnRunsTheFinallyBlocks() {
        assertLog(
                "1:false|finally|2:false|9:true",
                PUSH
                        + """
                        async function* g() {
                          try { yield 1; } finally { log.push('finally'); yield 2; }
                        }
                        var i = g();
                        i.next().then(push);
                        i.return(9).then(push);
                        i.next().then(push);
                        """);
    }

    @Test
    void throwReachesTheBody() {
        assertLog(
                "1:false|caught x:false|undefined:true|error y",
                PUSH
                        + """
                        async function* g() {
                          try { yield 1; } catch (e) { yield 'caught ' + e; }
                        }
                        var i = g();
                        i.next().then(push);
                        i.throw('x').then(push);
                        i.next().then(push);
                        i.throw('y').catch(fail);
                        """);
    }

    @Test
    void throwBeforeTheFirstNextCompletes() {
        assertLog(
                "error x|undefined:true",
                PUSH
                        + """
                        async function* g() { log.push('never'); }
                        var i = g();
                        i.throw('x').catch(fail);
                        i.next().then(push);
                        """);
    }

    @Test
    void methodsRejectABadReceiver() {
        assertLog(
                "TypeError",
                """
                async function* g() {}
                var next = Object.getPrototypeOf(g.prototype).next;
                next.call({}).catch(function (e) { log.push(e.name); });
                """);
    }

    @Test
    void yieldStarOfAnAsyncGenerator() {
        assertLog(
                "1:false|2:false|3:true",
                PUSH
                        + """
                        async function* a() { yield 1; yield 2; return 3; }
                        async function* g() { return yield* a(); }
                        var i = g();
                        i.next().then(push);
                        i.next().then(push);
                        i.next().then(push);
                        """);
    }

    @Test
    void yieldStarOfASyncIterableAwaitsItsValues() {
        assertLog(
                "1:false|2:false|done",
                PUSH
                        + """
                        async function* g() { yield* [Promise.resolve(1), 2]; log.push('done'); }
                        var i = g();
                        i.next().then(push);
                        i.next().then(push);
                        i.next();
                        """);
    }

    @Test
    void yieldStarForwardsThrowAndReturn() {
        assertLog(
                "1:false|caught x:false|closed|5:true",
                PUSH
                        + """
                        async function* a() {
                          try {
                            try { yield 1; } catch (e) { yield 'caught ' + e; }
                          } finally { log.push('closed'); }
                        }
                        async function* g() { yield* a(); }
                        var i = g();
                        i.next().then(push);
                        i.throw('x').then(push);
                        i.return(5).then(push);
                        """);
    }

    @Test
    void methodsOfClassesAndObjects() {
        assertLog(
                "1:false|2:false|3:false",
                PUSH
                        + """
                        class C { async *m() { yield 1; } static async *s() { yield 2; } }
                        var o = { async *m() { yield 3; } };
                        (async function () {
                          push(await new C().m().next());
                          push(await C.s().next());
                          push(await o.m().next());
                        })();
                        """);
    }

    @Test
    void aSyncGeneratorMayYieldInAFinallyOfAReturn() {
        assertLog(
                "1:false|finally:false|9:true",
                PUSH
                        + """
                        function* g() { try { yield 1; } finally { yield 'finally'; } }
                        var i = g();
                        push(i.next());
                        push(i.return(9));
                        push(i.next());
                        """);
    }
}
