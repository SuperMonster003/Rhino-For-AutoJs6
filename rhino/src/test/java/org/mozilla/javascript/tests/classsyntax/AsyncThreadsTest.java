/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativePromise;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;

/**
 * AutoJs6 async (ROADMAP T12, appendix C Q64 and Q65): promises in an embedder with several script
 * threads. A {@link Loop} stands for a thread with an event loop and a Context of its own; {@link
 * Host} is what the embedder does in {@code Context.forwardTask}.
 */
class AsyncThreadsTest {

    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    /** A thread with an event loop: a single task thread that runs microtasks after each task. */
    private static final class Loop implements AutoCloseable {
        private final ExecutorService executor;
        private final Host host;

        Loop(Host host, String name) {
            this.host = host;
            executor = Executors.newSingleThreadExecutor(r -> new Thread(r, name));
            run(cx -> null);
        }

        void post(Runnable task) {
            executor.execute(
                    () -> {
                        task.run();
                        Context.getCurrentContext().processMicrotasks();
                    });
        }

        <T> T run(Function<HostContext, T> task) {
            try {
                return executor.submit(
                                () -> {
                                    HostContext cx = (HostContext) Context.getCurrentContext();
                                    if (cx == null) {
                                        Host.loop.set(this);
                                        cx = (HostContext) host.enterContext();
                                    }
                                    T result = task.apply(cx);
                                    cx.processMicrotasks();
                                    return result;
                                })
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() {
            executor.execute(Context::exit);
            executor.shutdown();
        }
    }

    private static final class Host extends ContextFactory {
        static final ThreadLocal<Loop> loop = new ThreadLocal<>();
        Loop main;

        @Override
        protected Context makeContext() {
            Context cx = new HostContext(this, loop.get());
            cx.setLanguageVersion(Context.VERSION_ES6);
            return cx;
        }
    }

    private static final class HostContext extends Context {
        private final Host host;
        private final Loop loop;

        HostContext(Host host, Loop loop) {
            super(host);
            this.host = host;
            this.loop = loop;
        }

        @Override
        public boolean forwardTask(Context home, Scriptable owner, Runnable task) {
            Loop target =
                    home != null ? ((HostContext) home).loop : loop != null ? null : host.main;
            if (target == null || target == loop) {
                return false;
            }
            target.post(task);
            return true;
        }
    }

    private void install(Context cx, TopLevel scope) {
        Scriptable global = scope.getGlobalThis();
        global.put(
                "where",
                global,
                new LambdaFunction(
                        scope, "where", 0, (c, s, t, a) -> Thread.currentThread().getName()));
        global.put(
                "note",
                global,
                new LambdaFunction(
                        scope,
                        "note",
                        1,
                        (c, s, t, a) -> {
                            log.add(a[0] + "@" + Thread.currentThread().getName());
                            return Undefined.instance;
                        }));
        cx.evaluateString(
                scope,
                "var settle, fail; var p = new Promise(function (y, n) { settle = y; fail = n; });",
                "setup.js",
                1,
                null);
    }

    private void callOn(Thread thread) throws InterruptedException {
        thread.start();
        thread.join();
    }

    private void callInContext(Host host, TopLevel scope, String name) {
        Context cx = host.enterContext();
        try {
            Scriptable global = scope.getGlobalThis();
            Callable function = (Callable) global.get(name, global);
            function.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, new Object[] {1});
        } finally {
            Context.exit();
        }
    }

    @Test
    void aSettlementOutsideTheLoopsRunsOnTheMainLoop() throws Exception {
        Host host = new Host();
        try (Loop main = new Loop(host, "main")) {
            host.main = main;
            TopLevel scope =
                    main.run(
                            cx -> {
                                TopLevel s = cx.initStandardObjects();
                                install(cx, s);
                                cx.evaluateString(
                                        s,
                                        "p.then(function (v) { note('then ' + v); });"
                                                + "(async function () { note('await ' + await p); })();",
                                        "t.js",
                                        1,
                                        null);
                                return s;
                            });
            assertTrue(log.isEmpty());
            callOn(new Thread(() -> callInContext(host, scope, "settle"), "binder"));
            main.run(cx -> null);
            assertEquals(List.of("then 1@main", "await 1@main"), log);
        }
    }

    @Test
    void aRejectionOutsideTheLoopsIsTrackedOnTheMainLoop() throws Exception {
        Host host = new Host();
        try (Loop main = new Loop(host, "main")) {
            host.main = main;
            TopLevel scope =
                    main.run(
                            cx -> {
                                cx.setTrackUnhandledPromiseRejections(true);
                                TopLevel s = cx.initStandardObjects();
                                install(cx, s);
                                return s;
                            });
            callOn(new Thread(() -> callInContext(host, scope, "fail"), "binder"));
            assertEquals(
                    1,
                    (int)
                            main.run(
                                    cx -> {
                                        int[] count = new int[1];
                                        cx.getUnhandledPromiseTracker().process(v -> count[0]++);
                                        return count[0];
                                    }));
        }
    }

    @Test
    void awaitResumesOnTheThreadOfTheFunctionAndThenWhereThePromiseSettles() {
        Host host = new Host();
        try (Loop first = new Loop(host, "first");
                Loop second = new Loop(host, "second")) {
            host.main = first;
            TopLevel scope =
                    first.run(
                            cx -> {
                                TopLevel s = cx.initStandardObjects();
                                install(cx, s);
                                cx.evaluateString(
                                        s,
                                        "p.then(function (v) { note('then ' + v); });"
                                                + "(async function () { note('before'); await p; note('after'); })();"
                                                + "(async function () {"
                                                + "  for await (var v of [p]) note('for await ' + v);"
                                                + "})();",
                                        "t.js",
                                        1,
                                        null);
                                return s;
                            });
            second.run(
                    cx -> {
                        callInContext(host, scope, "settle");
                        return null;
                    });
            first.run(cx -> null);
            second.run(cx -> null);
            first.run(cx -> null);
            assertEquals(
                    List.of("before@first", "then 1@second", "after@first", "for await 1@first"),
                    log);
        }
    }

    @Test
    void anAwaitThatItsThreadSettlesStaysInItsQueue() {
        Host host = new Host();
        try (Loop main = new Loop(host, "main")) {
            host.main = main;
            main.run(
                    cx -> {
                        TopLevel s = cx.initStandardObjects();
                        install(cx, s);
                        cx.evaluateString(
                                s,
                                "(async function () { await p; note('await'); })();"
                                        + "p.then(function () { note('then'); });"
                                        + "settle(1);"
                                        + "note('sync');",
                                "t.js",
                                1,
                                null);
                        return null;
                    });
            assertEquals(List.of("sync@main", "await@main", "then@main"), log);
        }
    }

    @Test
    void aReactionAddedWhileAnotherThreadSettlesIsNotLost() throws Exception {
        Host host = new Host();
        int rounds = 20000;
        AtomicInteger reactions = new AtomicInteger();
        try (Loop first = new Loop(host, "first");
                Loop second = new Loop(host, "second")) {
            host.main = first;
            TopLevel scope = first.run(cx -> cx.initStandardObjects());
            Callable count =
                    new LambdaFunction(
                            scope,
                            1,
                            (c, s, t, a) -> {
                                reactions.incrementAndGet();
                                return Undefined.instance;
                            });
            CyclicBarrier barrier = new CyclicBarrier(2);
            NativePromise[] promise = new NativePromise[1];
            for (int i = 0; i < rounds; i++) {
                Object[] resolvers =
                        first.run(
                                cx -> {
                                    Scriptable pair =
                                            (Scriptable)
                                                    cx.evaluateString(
                                                            scope,
                                                            "Promise.withResolvers()",
                                                            "t.js",
                                                            1,
                                                            null);
                                    promise[0] = (NativePromise) pair.get("promise", pair);
                                    return new Object[] {pair.get("resolve", pair)};
                                });
                first.post(
                        () -> {
                            await(barrier);
                            Context cx = Context.getCurrentContext();
                            Callable then = (Callable) promise[0].getPrototype().get("then", null);
                            then.call(cx, scope, promise[0], new Object[] {count});
                        });
                second.post(
                        () -> {
                            await(barrier);
                            Context cx = Context.getCurrentContext();
                            ((Callable) resolvers[0])
                                    .call(cx, scope, Undefined.instance, new Object[] {1});
                        });
                first.run(cx -> null);
                second.run(cx -> null);
            }
            assertEquals(rounds, reactions.get());
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aRejectionHandledOnAnotherThreadIsNotReported() {
        Host host = new Host();
        try (Loop first = new Loop(host, "first");
                Loop second = new Loop(host, "second")) {
            host.main = first;
            TopLevel scope =
                    first.run(
                            cx -> {
                                cx.setTrackUnhandledPromiseRejections(true);
                                TopLevel s = cx.initStandardObjects();
                                install(cx, s);
                                return s;
                            });
            first.run(
                    cx -> {
                        cx.evaluateString(scope, "fail(1);", "t.js", 1, null);
                        assertEquals(1, cx.getUnhandledPromiseTracker().enumerate().size());
                        return null;
                    });
            second.run(
                    cx ->
                            cx.evaluateString(
                                    scope,
                                    "p.catch(function (v) { note('caught ' + v); });",
                                    "t.js",
                                    1,
                                    null));
            int[] reported = new int[1];
            first.run(
                    cx -> {
                        cx.getUnhandledPromiseTracker().process(v -> reported[0]++);
                        return null;
                    });
            assertEquals(0, reported[0]);
            assertEquals(List.of("caught 1@second"), log);
        }
    }
}
