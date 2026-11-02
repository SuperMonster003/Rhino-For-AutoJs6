/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.mozilla.javascript.tests.classsyntax.AsyncScripts.assertLog;

import org.junit.jupiter.api.Test;

/** AutoJs6 async (ROADMAP P9.2): {@code for await} and %AsyncFromSyncIteratorPrototype%. */
class ForAwaitTest {

    private static final String RUN =
            "function run(f) { f().catch(function (e) { log.push('error ' + e); }); }\n";

    private static final String COUNTER =
            """
            function counter(n, closed) {
              var i = 0;
              return {
                [Symbol.asyncIterator]() { return this; },
                next() {
                  i++;
                  return Promise.resolve({ value: i, done: i > n });
                },
                return() {
                  log.push('return');
                  return closed;
                }
              };
            }
            """;

    @Test
    void anAsyncIterable() {
        assertLog(
                "1|2|3|end",
                RUN
                        + COUNTER
                        + """
                        run(async function () {
                          for await (var x of counter(3)) log.push(x);
                          log.push('end');
                        });
                        """);
    }

    @Test
    void aSyncIterableAwaitsItsValues() {
        assertLog(
                "1|2|3",
                RUN
                        + """
                        var values = [Promise.resolve(1), 2, Promise.resolve(3)];
                        run(async function () {
                          for await (const x of values) log.push(x);
                        });
                        """);
    }

    @Test
    void anAsyncGenerator() {
        assertLog(
                "1|2",
                RUN
                        + """
                        async function* g() { yield 1; await null; yield 2; }
                        run(async function () { for await (let x of g()) log.push(x); });
                        """);
    }

    @Test
    void asyncIteratorWinsOverIterator() {
        assertLog(
                "async",
                RUN
                        + """
                        var o = {
                          [Symbol.iterator]() { throw 'sync'; },
                          [Symbol.asyncIterator]() {
                            var done = false;
                            return {
                              next() {
                                var r = { value: 'async', done: done };
                                done = true;
                                return r;
                              }
                            };
                          }
                        };
                        run(async function () { for await (var x of o) log.push(x); });
                        """);
    }

    @Test
    void breakClosesTheIteratorAndAwaitsIt() {
        assertLog(
                "1|return|after",
                RUN
                        + COUNTER
                        + """
                        run(async function () {
                          for await (var x of counter(3, Promise.resolve({}))) {
                            log.push(x);
                            break;
                          }
                          log.push('after');
                        });
                        """);
    }

    @Test
    void theResultOfCloseHasToBeAnObject() {
        assertLog(
                "return|error TypeError",
                COUNTER
                        + """
                        (async function () {
                          for await (var x of counter(3, Promise.resolve(1))) break;
                        })().catch(function (e) { log.push('error ' + e.name); });
                        """);
    }

    @Test
    void anExceptionIgnoresTheClose() {
        assertLog(
                "return|error body",
                RUN
                        + COUNTER
                        + """
                        run(async function () {
                          for await (var x of counter(3, Promise.reject('close'))) throw 'body';
                        });
                        """);
    }

    @Test
    void aReturnInTheBodyClosesTheIterator() {
        assertLog(
                "return|7",
                COUNTER
                        + """
                        (async function () {
                          for await (var x of counter(3, {})) return 7;
                        })().then(function (v) { log.push(v); });
                        """);
    }

    @Test
    void aRejectedNextEndsTheLoop() {
        assertLog(
                "error r",
                RUN
                        + """
                        var o = {
                          [Symbol.asyncIterator]() {
                            return {
                              next() { return Promise.reject('r'); },
                              return() { log.push('no'); return {}; }
                            };
                          }
                        };
                        run(async function () { for await (var x of o) log.push(x); });
                        """);
    }

    @Test
    void aRejectedValueClosesTheSyncIterator() {
        assertLog(
                "closed|error r",
                RUN
                        + """
                        var it = {
                          [Symbol.iterator]() { return this; },
                          next() { return { value: Promise.reject('r'), done: false }; },
                          return() { log.push('closed'); return {}; }
                        };
                        run(async function () { for await (var x of it) log.push(x); });
                        """);
    }

    @Test
    void destructuringHeads() {
        assertLog(
                "1,2|3|4,5|6",
                RUN
                        + """
                        run(async function () {
                          var a, b;
                          for await ([a, b] of [[1, 2]]) log.push(a + ',' + b);
                          for await (const { x } of [{ x: 3 }]) log.push(x);
                          for await (let [c, d = 5] of [[4]]) log.push(c + ',' + d);
                          var o = {};
                          for await (o.p of [6]) log.push(o.p);
                        });
                        """);
    }

    @Test
    void shorthandInitializersInLoopHeads() {
        // Without strict mode, eval is a plain name; a pattern in the head of a for-of loop was
        // rejected as an object literal.
        assertLog(
                "1|2|0",
                RUN
                        + """
                        run(async function () {
                          var a, b;
                          for ({ a = 1 } of [{}]) log.push(a);
                          for await ({ b = 2 } of [{}]) log.push(b);
                          (function () { for ({ eval = 0 } of [{}]) log.push(eval); })();
                        });
                        """);
    }

    @Test
    void labelsAndContinue() {
        assertLog(
                "1|3|return",
                RUN
                        + COUNTER
                        + """
                        run(async function () {
                          outer: for await (var x of counter(4, {})) {
                            if (x == 2) continue;
                            if (x == 4) break outer;
                            log.push(x);
                          }
                        });
                        """);
    }

    @Test
    void asyncIsAnIdentifierInTheHead() {
        assertLog(
                "1",
                RUN
                        + """
                        run(async function () {
                          var async;
                          for await (async of [1]) log.push(async);
                        });
                        """);
    }

    @Test
    void insideAnAsyncGeneratorAndAMethod() {
        assertLog(
                "1|2",
                RUN
                        + """
                        class C {
                          async *m(values) { for await (var v of values) yield v; }
                          async run() {
                            for await (var x of this.m([1, Promise.resolve(2)])) log.push(x);
                          }
                        }
                        run(function () { return new C().run(); });
                        """);
    }

    @Test
    void notIterable() {
        assertLog(
                "TypeError",
                """
                (async function () { for await (var x of 1); })()
                    .catch(function (e) { log.push(e.name); });
                """);
    }
}
