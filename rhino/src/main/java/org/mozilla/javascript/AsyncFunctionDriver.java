/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6 async: runs the body of an async function (ROADMAP T7). The body is compiled like a
 * generator and {@code await} is a yield of an {@link AsyncRuntime.Await}, so the driver takes the
 * generator from one await to the next: it subscribes to the awaited promise and resumes the body
 * in the promise job. {@link NativeAsyncGenerator} builds its queue on the same loop.
 */
class AsyncFunctionDriver {

    private final JSFunction function;
    private final Object savedState;

    /** The top level scope of the function: the realm of the promises. */
    final VarScope scope;

    private NativePromise promise;
    private AsyncRuntime.Await suspended;
    private AsyncTracker tracker;
    private NativePromise.ReactionCall onFulfilled;
    private NativePromise.ReactionCall onRejected;

    AsyncFunctionDriver(JSFunction function, Object savedState) {
        this.function = function;
        this.savedState = savedState;
        scope = ScriptableObject.getTopLevelScope(function.getDeclarationScope());
    }

    /** The result of calling an async function, whose state is in {@code savedState}. */
    static Scriptable start(Context cx, JSFunction function, Object savedState) {
        AsyncFunctionDriver driver = new AsyncFunctionDriver(function, savedState);
        driver.promise = NativePromise.create(driver.scope);
        driver.run(cx, NativeGenerator.GENERATOR_SEND, Undefined.instance);
        return driver.promise;
    }

    /** Runs the body until it awaits, yields or ends. */
    final void run(Context cx, int operation, Object value) {
        int op = operation;
        Object sent = value;
        while (true) {
            Object result;
            try {
                result = function.resumeGenerator(cx, scope, op, savedState, sent);
            } catch (NativeGenerator.GeneratorClosedException e) {
                returned(cx, e.getValue());
                return;
            } catch (JavaScriptException e) {
                if (e.getValue() instanceof NativeIterator.StopIteration) {
                    returned(cx, ((NativeIterator.StopIteration) e.getValue()).getValue());
                } else {
                    threw(cx, e.getValue());
                }
                return;
            } catch (RhinoException e) {
                threw(cx, AsyncRuntime.thrown(e, scope, cx));
                return;
            }
            if (!(result instanceof AsyncRuntime.Await)) {
                yielded(cx, result);
                return;
            }
            AsyncRuntime.Await await = (AsyncRuntime.Await) result;
            if (await.skipped) {
                op = NativeGenerator.GENERATOR_SEND;
                sent = Undefined.instance;
                continue;
            }
            NativePromise awaited;
            try {
                awaited = AsyncRuntime.promiseResolve(cx, scope, await.value);
            } catch (RhinoException e) {
                op = await.quiet ? NativeGenerator.GENERATOR_SEND : NativeGenerator.GENERATOR_THROW;
                sent = await.quiet ? Undefined.instance : AsyncRuntime.thrown(e, scope, cx);
                continue;
            }
            suspend(cx, await, awaited);
            return;
        }
    }

    private void suspend(Context cx, AsyncRuntime.Await await, NativePromise awaited) {
        if (onFulfilled == null) {
            onFulfilled =
                    (c, s, v) -> {
                        resumed();
                        AsyncRuntime.inTopCall(
                                c, scope, () -> run(c, NativeGenerator.GENERATOR_SEND, v));
                    };
            onRejected =
                    (c, s, v) -> {
                        boolean quiet = resumed().quiet;
                        AsyncRuntime.inTopCall(
                                c,
                                scope,
                                () -> {
                                    if (quiet) {
                                        run(c, NativeGenerator.GENERATOR_SEND, Undefined.instance);
                                    } else {
                                        run(c, NativeGenerator.GENERATOR_THROW, v);
                                    }
                                });
                    };
        }
        await.value = null;
        await.sourceName = function.getDescriptor().getSourceName();
        suspended = await;
        tracker = AsyncTracker.of(cx);
        tracker.add(await);
        awaited.react(cx, scope, onFulfilled, onRejected);
    }

    private AsyncRuntime.Await resumed() {
        AsyncRuntime.Await await = suspended;
        suspended = null;
        tracker.remove(await);
        return await;
    }

    /** The body returned {@code value}. */
    void returned(Context cx, Object value) {
        promise.resolve(cx, scope, value);
    }

    /** The body threw {@code value}. */
    void threw(Context cx, Object value) {
        promise.reject(cx, scope, value);
    }

    /** The body yielded {@code value}, which only a generator does. */
    void yielded(Context cx, Object value) {
        throw Kit.codeBug();
    }
}
