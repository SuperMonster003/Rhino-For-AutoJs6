/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.mozilla.javascript.ClassDescriptor.Destination.CTOR;
import static org.mozilla.javascript.Symbol.Kind.REGULAR;

import java.io.Serial;

/**
 * AutoJs6 async: the iterator that {@code for await} and {@code yield*} use for an object that is
 * only iterable (ES2024 27.1.4, %AsyncFromSyncIteratorPrototype%; ROADMAP P9.2). Scripts never get
 * hold of it.
 */
final class AsyncFromSyncIterator extends ScriptableObject {
    @Serial private static final long serialVersionUID = 2954213183029436431L;

    private static final SymbolKey TAG = new SymbolKey("AsyncFromSyncIteratorPrototype", REGULAR);

    private static final ClassDescriptor DESCRIPTOR =
            new ClassDescriptor.Builder(TAG)
                    .withMethod(CTOR, "next", 1, AsyncFromSyncIterator::js_next)
                    .withMethod(CTOR, "return", 1, AsyncFromSyncIterator::js_return)
                    .withMethod(CTOR, "throw", 1, AsyncFromSyncIterator::js_throw)
                    .build();

    private final transient AsyncRuntime.IteratorRecord sync;

    private AsyncFromSyncIterator(AsyncRuntime.IteratorRecord sync) {
        this.sync = sync;
    }

    @Override
    public String getClassName() {
        return "Object";
    }

    static void init(Context cx, TopLevel top, Scriptable asyncIteratorPrototype) {
        NativeObject prototype = new NativeObject();
        ScriptRuntime.setObjectProtoAndParent(prototype, top);
        DESCRIPTOR.populateGlobal(cx, top, prototype, false);
        AsyncRuntime.hide(top, TAG);
        prototype.setPrototype(asyncIteratorPrototype);
        top.associateValue(TAG, prototype);
    }

    /** CreateAsyncFromSyncIterator. */
    static AsyncRuntime.IteratorRecord create(
            Context cx, VarScope scope, AsyncRuntime.IteratorRecord sync) {
        AsyncFromSyncIterator iterator = new AsyncFromSyncIterator(sync);
        iterator.setParentScope(ScriptableObject.getTopLevelScope(scope));
        AsyncRuntime.intrinsic(cx, scope, AsyncRuntime.ASYNC_ITERATOR_TAG);
        iterator.setPrototype(
                (Scriptable)
                        ScriptableObject.getTopScopeValue(
                                ScriptableObject.getTopLevelScope(scope), TAG));
        return AsyncRuntime.iteratorRecord(iterator, cx, scope);
    }

    private static Object[] present(Object[] args) {
        return args.length >= 1 ? new Object[] {args[0]} : ScriptRuntime.emptyArgs;
    }

    private static Object js_next(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        AsyncRuntime.IteratorRecord sync = ((AsyncFromSyncIterator) thisObj).sync;
        VarScope scope = f.getDeclarationScope();
        NativePromise promise = NativePromise.create(scope);
        Object result;
        try {
            result = sync.callNext(cx, scope, present(args));
            if (!ScriptRuntime.isObject(result)) {
                throw ScriptRuntime.typeErrorById("msg.iterator.primitive");
            }
        } catch (RhinoException e) {
            return rejected(cx, scope, promise, e);
        }
        return continuation(cx, scope, promise, sync, result, true);
    }

    private static Object js_return(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        AsyncRuntime.IteratorRecord sync = ((AsyncFromSyncIterator) thisObj).sync;
        VarScope scope = f.getDeclarationScope();
        NativePromise promise = NativePromise.create(scope);
        Object value = args.length >= 1 ? args[0] : Undefined.instance;
        Object result;
        try {
            Callable method =
                    AsyncRuntime.getMethod(sync.iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
            if (method == null) {
                promise.resolve(
                        cx, scope, ES6Iterator.makeIteratorResult(cx, scope, Boolean.TRUE, value));
                return promise;
            }
            result = method.call(cx, scope, sync.iterator, present(args));
        } catch (RhinoException e) {
            return rejected(cx, scope, promise, e);
        }
        if (!ScriptRuntime.isObject(result)) {
            return rejectedTypeError(cx, scope, promise);
        }
        return continuation(cx, scope, promise, sync, result, false);
    }

    private static Object js_throw(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        AsyncRuntime.IteratorRecord sync = ((AsyncFromSyncIterator) thisObj).sync;
        VarScope scope = f.getDeclarationScope();
        NativePromise promise = NativePromise.create(scope);
        Object result;
        try {
            Callable method = AsyncRuntime.getMethod(sync.iterator, "throw", cx, scope);
            if (method == null) {
                // A protocol violation: close the iterator, then a TypeError.
                sync.done = true;
                Scriptable iterator = (Scriptable) sync.iterator;
                Callable close =
                        AsyncRuntime.getMethod(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
                if (close != null) {
                    Object closed = close.call(cx, scope, iterator, ScriptRuntime.emptyArgs);
                    if (!ScriptRuntime.isObject(closed)) {
                        return rejectedTypeError(cx, scope, promise);
                    }
                }
                return rejectedTypeError(cx, scope, promise);
            }
            result = method.call(cx, scope, sync.iterator, present(args));
        } catch (RhinoException e) {
            return rejected(cx, scope, promise, e);
        }
        if (!ScriptRuntime.isObject(result)) {
            return rejectedTypeError(cx, scope, promise);
        }
        return continuation(cx, scope, promise, sync, result, true);
    }

    private static Object rejected(
            Context cx, VarScope scope, NativePromise promise, RhinoException e) {
        promise.reject(cx, scope, AsyncRuntime.thrown(e, scope, cx));
        return promise;
    }

    private static Object rejectedTypeError(Context cx, VarScope scope, NativePromise promise) {
        return rejected(cx, scope, promise, ScriptRuntime.typeErrorById("msg.iterator.primitive"));
    }

    /** AsyncFromSyncIteratorContinuation. */
    private static Object continuation(
            Context cx,
            VarScope scope,
            NativePromise promise,
            AsyncRuntime.IteratorRecord sync,
            Object result,
            boolean closeOnRejection) {
        boolean done;
        Object value;
        try {
            done = ScriptRuntime.isIteratorDone(cx, result);
            value = ScriptRuntime.getObjectProp(result, ES6Iterator.VALUE_PROPERTY, cx, scope);
        } catch (RhinoException e) {
            return rejected(cx, scope, promise, e);
        }
        NativePromise wrapper;
        try {
            wrapper = AsyncRuntime.promiseResolve(cx, scope, value);
        } catch (RhinoException e) {
            if (!done && closeOnRejection) {
                closeQuietly(cx, scope, sync);
            }
            return rejected(cx, scope, promise, e);
        }
        wrapper.react(
                cx,
                scope,
                (c, s, v) ->
                        promise.resolve(
                                c,
                                scope,
                                ES6Iterator.makeIteratorResult(c, scope, Boolean.valueOf(done), v)),
                (c, s, reason) -> {
                    if (!done && closeOnRejection) {
                        AsyncRuntime.inTopCall(c, scope, () -> closeQuietly(c, scope, sync));
                    }
                    promise.reject(c, scope, reason);
                });
        return promise;
    }

    /** IteratorClose with a throw completion: the errors of {@code return} are dropped. */
    private static void closeQuietly(Context cx, VarScope scope, AsyncRuntime.IteratorRecord sync) {
        try {
            Callable method =
                    AsyncRuntime.getMethod(sync.iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
            if (method != null) {
                method.call(cx, scope, sync.iterator, ScriptRuntime.emptyArgs);
            }
        } catch (RhinoException e) {
            // The rejection of the value wins.
        }
    }
}
