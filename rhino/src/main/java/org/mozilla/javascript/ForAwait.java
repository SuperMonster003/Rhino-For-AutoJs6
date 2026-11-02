/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6 async: the steps of a {@code for await} loop (ROADMAP P9.2). The IR of the loop is that
 * of a for-of loop where the enumeration is a record kept in the local of the loop and the awaits
 * are yields in the tree (see {@link AsyncLowering#forAwait}):
 *
 * <pre>
 * OPEN(iterable); while (STEP(await NEXT)) { head = VALUE; body }
 * </pre>
 *
 * inside {@code try { ... } catch { await CLOSE_QUIET; rethrow } finally { CLOSE_CHECK(await CLOSE)
 * }}. It is public for the compiler in {@code org.mozilla.javascript.optimizer}; it is not an API.
 */
public final class ForAwait {

    public static final int OPEN = 0;
    public static final int NEXT = 1;
    public static final int STEP = 2;
    public static final int VALUE = 3;
    public static final int CLOSE = 4;
    public static final int CLOSE_CHECK = 5;
    public static final int CLOSE_QUIET = 6;

    /** The operand of an await that has nothing to wait for. */
    static final Object NOTHING = new Object();

    /** The operand of an await whose rejection is ignored. */
    static final class Quiet {
        final Object value;

        Quiet(Object value) {
            this.value = value;
        }
    }

    private static final class Loop {
        final AsyncRuntime.IteratorRecord iterator;

        /** The body may be running: the loop has to close the iterator when it leaves. */
        boolean open;

        boolean closing;
        Object value = Undefined.instance;

        Loop(AsyncRuntime.IteratorRecord iterator) {
            this.iterator = iterator;
        }
    }

    private ForAwait() {}

    /** Whether the step has a child, which {@code value} is. */
    public static boolean hasValue(int step) {
        return step == OPEN || step == STEP || step == CLOSE_CHECK;
    }

    /**
     * Runs a step of the loop whose record is {@code record}: OPEN returns the record, STEP whether
     * the loop goes on.
     */
    public static Object step(Object value, Object record, int step, Context cx, VarScope scope) {
        if (step == OPEN) {
            return new Loop(AsyncRuntime.getAsyncIterator(value, cx, scope));
        }
        Loop loop = (Loop) record;
        switch (step) {
            case NEXT:
                loop.open = false;
                return loop.iterator.callNext(cx, scope, ScriptRuntime.emptyArgs);
            case STEP:
                if (!ScriptRuntime.isObject(value)) {
                    throw ScriptRuntime.typeErrorById("msg.iterator.primitive");
                }
                if (ScriptRuntime.isIteratorDone(cx, value)) {
                    return Boolean.FALSE;
                }
                loop.value =
                        ScriptRuntime.getObjectProp(value, ES6Iterator.VALUE_PROPERTY, cx, scope);
                loop.open = true;
                return Boolean.TRUE;
            case VALUE:
                return loop.value;
            case CLOSE:
                return close(loop, cx, scope);
            case CLOSE_CHECK:
                if (loop != null && loop.closing) {
                    loop.closing = false;
                    if (!ScriptRuntime.isObject(value)) {
                        throw ScriptRuntime.typeErrorById("msg.iterator.primitive");
                    }
                }
                return Undefined.instance;
            case CLOSE_QUIET:
                return closeQuietly(loop, cx, scope);
            default:
                throw Kit.codeBug();
        }
    }

    private static Object close(Loop loop, Context cx, VarScope scope) {
        if (loop == null || !loop.open) {
            return NOTHING;
        }
        loop.open = false;
        Object iterator = loop.iterator.iterator;
        Callable method = AsyncRuntime.getMethod(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
        if (method == null) {
            return NOTHING;
        }
        Object result = method.call(cx, scope, iterator, ScriptRuntime.emptyArgs);
        loop.closing = true;
        return result;
    }

    private static Object closeQuietly(Loop loop, Context cx, VarScope scope) {
        if (loop == null || !loop.open) {
            return NOTHING;
        }
        loop.open = false;
        Object iterator = loop.iterator.iterator;
        try {
            Callable method =
                    AsyncRuntime.getMethod(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
            if (method == null) {
                return NOTHING;
            }
            return new Quiet(method.call(cx, scope, iterator, ScriptRuntime.emptyArgs));
        } catch (RhinoException e) {
            return NOTHING;
        }
    }
}
