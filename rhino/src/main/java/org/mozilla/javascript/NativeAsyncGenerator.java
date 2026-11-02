/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.mozilla.javascript.ClassDescriptor.Builder.value;
import static org.mozilla.javascript.ClassDescriptor.Destination.CTOR;
import static org.mozilla.javascript.Symbol.Kind.REGULAR;

import java.io.Serial;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * AutoJs6 async: an async generator object (ES2024 27.6, ROADMAP P9.3). The requests of {@code
 * next}, {@code return} and {@code throw} wait in a queue; the body runs on {@link
 * AsyncFunctionDriver}, whose awaits are yields of {@link AsyncRuntime.Await}. Every other yield of
 * the body is an AsyncGeneratorYield, and a {@code yield*} is run here: the iterator is an async
 * one, so each of its steps takes an await that is not in the body.
 */
public final class NativeAsyncGenerator extends ScriptableObject {
    @Serial private static final long serialVersionUID = 6318241125090238723L;

    static final SymbolKey ASYNC_GENERATOR_TAG = new SymbolKey("AsyncGeneratorPrototype", REGULAR);

    private static final ClassDescriptor DESCRIPTOR =
            new ClassDescriptor.Builder(ASYNC_GENERATOR_TAG)
                    .withMethod(CTOR, "next", 1, NativeAsyncGenerator::js_next)
                    .withMethod(CTOR, "return", 1, NativeAsyncGenerator::js_return)
                    .withMethod(CTOR, "throw", 1, NativeAsyncGenerator::js_throw)
                    .withProp(
                            CTOR,
                            SymbolKey.TO_STRING_TAG,
                            value("AsyncGenerator", DONTENUM | READONLY))
                    .build();

    private enum State {
        SUSPENDED_START,
        SUSPENDED_YIELD,
        EXECUTING,
        DRAINING_QUEUE,
        COMPLETED
    }

    private enum Kind {
        NEXT,
        RETURN,
        THROW
    }

    private static final class Request {
        final Kind kind;
        final Object value;
        final NativePromise promise;

        Request(Kind kind, Object value, NativePromise promise) {
            this.kind = kind;
            this.value = value;
            this.promise = promise;
        }
    }

    private final transient ArrayDeque<Request> queue = new ArrayDeque<>();
    private transient Driver driver;
    private State state = State.SUSPENDED_START;

    /** The inner iterator of the {@code yield*} that is running. */
    private transient AsyncRuntime.IteratorRecord delegate;

    private NativeAsyncGenerator() {}

    /** OrdinaryCreateFromConstructor(function, "%AsyncGeneratorFunction.prototype.prototype%"). */
    static NativeAsyncGenerator create(JSFunction function, Object savedState) {
        NativeAsyncGenerator generator = new NativeAsyncGenerator();
        generator.driver = generator.new Driver(function, savedState);
        VarScope top = generator.driver.scope;
        generator.setParentScope(top);
        Object prototype = ScriptableObject.getProperty(function, "prototype");
        if (prototype instanceof Scriptable) {
            generator.setPrototype((Scriptable) prototype);
        } else {
            Context cx = Context.getCurrentContext();
            generator.setPrototype(AsyncRuntime.intrinsic(cx, top, ASYNC_GENERATOR_TAG));
        }
        return generator;
    }

    static NativeObject initPrototype(
            Context cx, TopLevel top, Scriptable asyncIteratorPrototype, boolean sealed) {
        NativeObject prototype = new NativeObject();
        ScriptRuntime.setObjectProtoAndParent(prototype, top);
        DESCRIPTOR.populateGlobal(cx, top, prototype, false);
        AsyncRuntime.hide(top, ASYNC_GENERATOR_TAG);
        prototype.setPrototype(asyncIteratorPrototype);
        return prototype;
    }

    @Override
    public String getClassName() {
        return "AsyncGenerator";
    }

    private static Object js_next(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        return request(cx, f, thisObj, Kind.NEXT, args);
    }

    private static Object js_return(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        return request(cx, f, thisObj, Kind.RETURN, args);
    }

    private static Object js_throw(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        return request(cx, f, thisObj, Kind.THROW, args);
    }

    private static Object request(
            Context cx, JSFunction f, Object thisObj, Kind kind, Object[] args) {
        VarScope scope = ScriptableObject.getTopLevelScope(f.getDeclarationScope());
        NativePromise promise = NativePromise.create(scope);
        Object value = args.length >= 1 ? args[0] : Undefined.instance;
        if (!(thisObj instanceof NativeAsyncGenerator)) {
            promise.reject(
                    cx,
                    scope,
                    ScriptRuntime.wrapException(
                            ScriptRuntime.typeErrorById(
                                    "msg.incompat.call", kind.name().toLowerCase(Locale.ROOT)),
                            scope,
                            cx));
            return promise;
        }
        ((NativeAsyncGenerator) thisObj).enqueue(cx, scope, kind, value, promise);
        return promise;
    }

    private void enqueue(
            Context cx, VarScope scope, Kind kind, Object value, NativePromise promise) {
        switch (kind) {
            case NEXT:
                if (state == State.COMPLETED) {
                    promise.resolve(
                            cx,
                            scope,
                            ES6Iterator.makeIteratorResult(
                                    cx, scope, Boolean.TRUE, Undefined.instance));
                    return;
                }
                queue.add(new Request(kind, value, promise));
                if (state == State.SUSPENDED_START || state == State.SUSPENDED_YIELD) {
                    resume(cx, queue.peek());
                }
                return;
            case RETURN:
                queue.add(new Request(kind, value, promise));
                if (state == State.SUSPENDED_START || state == State.COMPLETED) {
                    state = State.DRAINING_QUEUE;
                    awaitReturn(cx);
                } else if (state == State.SUSPENDED_YIELD) {
                    resume(cx, queue.peek());
                }
                return;
            case THROW:
                if (state == State.SUSPENDED_START) {
                    state = State.COMPLETED;
                }
                if (state == State.COMPLETED) {
                    promise.reject(cx, scope, value);
                    return;
                }
                queue.add(new Request(kind, value, promise));
                if (state == State.SUSPENDED_YIELD) {
                    resume(cx, queue.peek());
                }
                return;
        }
    }

    /** AsyncGeneratorResume. */
    private void resume(Context cx, Request request) {
        boolean started = state != State.SUSPENDED_START;
        state = State.EXECUTING;
        if (started) {
            unwrapResumption(cx, request.kind, request.value);
        } else {
            driver.run(cx, NativeGenerator.GENERATOR_SEND, Undefined.instance);
        }
    }

    /** AsyncGeneratorUnwrapYieldResumption: a return awaits its value first. */
    private void unwrapResumption(Context cx, Kind kind, Object value) {
        if (kind != Kind.RETURN) {
            received(cx, kind, value);
            return;
        }
        AsyncRuntime.await(
                cx,
                driver.scope,
                value,
                (c, s, v) -> received(c, Kind.RETURN, v),
                (c, s, reason) -> received(c, Kind.THROW, reason));
    }

    /** What the yield resumes with. */
    private void received(Context cx, Kind kind, Object value) {
        if (delegate != null) {
            delegateStep(cx, kind, value);
            return;
        }
        resumeBody(cx, kind, value);
    }

    private void resumeBody(Context cx, Kind kind, Object value) {
        switch (kind) {
            case NEXT:
                driver.run(cx, NativeGenerator.GENERATOR_SEND, value);
                break;
            case RETURN:
                driver.run(
                        cx,
                        NativeGenerator.GENERATOR_CLOSE,
                        new NativeGenerator.GeneratorClosedException(value));
                break;
            case THROW:
                driver.run(cx, NativeGenerator.GENERATOR_THROW, value);
                break;
        }
    }

    /** AsyncGeneratorYield: completes the first request, then runs on with the next one. */
    private void yieldValue(Context cx, Object value) {
        completeStep(cx, Kind.NEXT, value, false);
        Request next = queue.peek();
        if (next != null) {
            unwrapResumption(cx, next.kind, next.value);
        } else {
            state = State.SUSPENDED_YIELD;
        }
    }

    /** AsyncGeneratorCompleteStep; {@code kind} tells a throw completion from a normal one. */
    private void completeStep(Context cx, Kind kind, Object value, boolean done) {
        Request request = queue.remove();
        VarScope scope = driver.scope;
        if (kind == Kind.THROW) {
            request.promise.reject(cx, scope, value);
        } else {
            request.promise.resolve(
                    cx,
                    scope,
                    ES6Iterator.makeIteratorResult(cx, scope, Boolean.valueOf(done), value));
        }
    }

    /** The end of the body. */
    private void finish(Context cx, Kind kind, Object value) {
        state = State.DRAINING_QUEUE;
        completeStep(cx, kind, value, true);
        drainQueue(cx);
    }

    /** AsyncGeneratorDrainQueue. */
    private void drainQueue(Context cx) {
        while (true) {
            Request next = queue.peek();
            if (next == null) {
                state = State.COMPLETED;
                return;
            }
            if (next.kind == Kind.RETURN) {
                awaitReturn(cx);
                return;
            }
            Object value = next.kind == Kind.NEXT ? Undefined.instance : next.value;
            completeStep(cx, next.kind, value, true);
        }
    }

    /** AsyncGeneratorAwaitReturn. */
    private void awaitReturn(Context cx) {
        AsyncRuntime.await(
                cx,
                driver.scope,
                queue.peek().value,
                (c, s, v) -> {
                    completeStep(c, Kind.NEXT, v, true);
                    drainQueue(c);
                },
                (c, s, reason) -> {
                    completeStep(c, Kind.THROW, reason, true);
                    drainQueue(c);
                });
    }

    /** {@code yield*}: GetIterator(value, async), then the first step. */
    private void delegateStart(Context cx, Object iterable) {
        try {
            delegate = AsyncRuntime.getAsyncIterator(iterable, cx, driver.scope);
        } catch (RhinoException e) {
            throwIntoBody(cx, e);
            return;
        }
        delegateStep(cx, Kind.NEXT, Undefined.instance);
    }

    private void throwIntoBody(Context cx, RhinoException e) {
        delegate = null;
        resumeBody(cx, Kind.THROW, AsyncRuntime.thrown(e, driver.scope, cx));
    }

    /** One round of the loop of YieldExpression : yield * AssignmentExpression. */
    private void delegateStep(Context cx, Kind kind, Object value) {
        VarScope scope = driver.scope;
        Object iterator = delegate.iterator;
        Object inner;
        try {
            Object[] args = {value};
            if (kind == Kind.NEXT) {
                inner = delegate.callNext(cx, scope, args);
            } else if (kind == Kind.THROW) {
                Callable method = AsyncRuntime.getMethod(iterator, "throw", cx, scope);
                if (method == null) {
                    delegateCloseForThrow(cx);
                    return;
                }
                inner = method.call(cx, scope, iterator, args);
            } else {
                Callable method =
                        AsyncRuntime.getMethod(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
                if (method == null) {
                    delegate = null;
                    AsyncRuntime.await(
                            cx,
                            scope,
                            value,
                            (c, s, v) -> resumeBody(c, Kind.RETURN, v),
                            (c, s, reason) -> resumeBody(c, Kind.THROW, reason));
                    return;
                }
                inner = method.call(cx, scope, iterator, args);
            }
        } catch (RhinoException e) {
            throwIntoBody(cx, e);
            return;
        }
        AsyncRuntime.await(
                cx,
                scope,
                inner,
                (c, s, result) -> delegateResult(c, kind, result),
                (c, s, reason) -> {
                    delegate = null;
                    resumeBody(c, Kind.THROW, reason);
                });
    }

    private void delegateResult(Context cx, Kind kind, Object result) {
        VarScope scope = driver.scope;
        boolean done;
        Object value;
        try {
            if (!ScriptRuntime.isObject(result)) {
                throw ScriptRuntime.typeErrorById("msg.iterator.primitive");
            }
            done = ScriptRuntime.isIteratorDone(cx, result);
            value = ScriptRuntime.getObjectProp(result, ES6Iterator.VALUE_PROPERTY, cx, scope);
        } catch (RhinoException e) {
            throwIntoBody(cx, e);
            return;
        }
        if (done) {
            delegate = null;
            resumeBody(cx, kind == Kind.RETURN ? Kind.RETURN : Kind.NEXT, value);
        } else {
            yieldValue(cx, value);
        }
    }

    /** The iterator has no {@code throw}: AsyncIteratorClose, then a TypeError. */
    private void delegateCloseForThrow(Context cx) {
        VarScope scope = driver.scope;
        Object iterator = delegate.iterator;
        RhinoException error =
                ScriptRuntime.typeErrorById("msg.isnt.function", "throw", "undefined");
        Callable method = AsyncRuntime.getMethod(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
        if (method == null) {
            throwIntoBody(cx, error);
            return;
        }
        Object inner = method.call(cx, scope, iterator, ScriptRuntime.emptyArgs);
        AsyncRuntime.await(
                cx,
                scope,
                inner,
                (c, s, result) -> {
                    if (!ScriptRuntime.isObject(result)) {
                        throwIntoBody(c, ScriptRuntime.typeErrorById("msg.iterator.primitive"));
                    } else {
                        throwIntoBody(c, error);
                    }
                },
                (c, s, reason) -> {
                    delegate = null;
                    resumeBody(c, Kind.THROW, reason);
                });
    }

    private final class Driver extends AsyncFunctionDriver {
        Driver(JSFunction function, Object savedState) {
            super(function, savedState);
        }

        @Override
        void returned(Context cx, Object value) {
            finish(cx, Kind.NEXT, value);
        }

        @Override
        void threw(Context cx, Object value) {
            finish(cx, Kind.THROW, value);
        }

        @Override
        void yielded(Context cx, Object value) {
            if (value instanceof ES6Generator.YieldStarResult) {
                delegateStart(cx, ((ES6Generator.YieldStarResult) value).getResult());
            } else {
                yieldValue(cx, value);
            }
        }
    }
}
