/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoJs6: the iterator of an array pattern outside a parameter list (ROADMAP P6.12, appendix C
 * Q25), so that {@code var [a, b] = new Set([1, 2])} binds 1 and 2: upstream read such patterns by
 * index. Not related to classes; public for the compiled code, not an API.
 *
 * <p>{@link BindingLowering} gives each array pattern a LOCAL_BLOCK whose local holds the {@link
 * IteratorRecord}; the {@link ClassOp#DESTRUCTURE} nodes of the pattern run the steps of
 * IteratorBindingInitialization / IteratorDestructuringAssignmentEvaluation on it:
 *
 * <ul>
 *   <li>{@link #OPEN}: GetIterator of the value, which reads {@code next} once;
 *   <li>{@link #STEP}: the value of the next element, undefined once the iterator is done (next is
 *       not called again); an error of {@code next}, {@code done} or {@code value} marks the
 *       iterator done, so that it is not closed;
 *   <li>{@link #SKIP}: an elision, which does not read {@code value};
 *   <li>{@link #VALUE}: the value of the last {@link #STEP} once more, for an element with a
 *       default, which reads it twice;
 *   <li>{@link #REST}: an array of the values the iterator has left;
 *   <li>{@link #FINISH}: the end of the pattern, IteratorClose when the iterator is not done;
 *   <li>{@link #CLOSE_QUIET} and {@link #CLOSE_RETURN}: IteratorClose when the statement holding
 *       the pattern ends with an exception (errors of {@code return} are ignored) or, in a
 *       generator, with a return during a yield inside the pattern; they do nothing once the
 *       pattern finished or the iterator is done.
 * </ul>
 *
 * <p>A plain array whose iteration can not be observed (the built-in {@code values} as {@code
 * Symbol.iterator} of its prototype and the built-in {@code next}, see {@link ArraySpread}) is read
 * by index, as the array iterator reads it (Get, against the length at each step), without creating
 * the iterator; should a {@code return} method turn up before the pattern finishes, the iterator is
 * created at the index the pattern reached and closed.
 */
public final class ArrayDestructuring {

    public static final int OPEN = 0;
    public static final int STEP = 1;
    public static final int SKIP = 2;
    public static final int REST = 3;
    public static final int FINISH = 4;
    public static final int CLOSE_QUIET = 5;
    public static final int CLOSE_RETURN = 6;
    public static final int VALUE = 7;

    private ArrayDestructuring() {}

    /** The iterator record of an array pattern. */
    static final class IteratorRecord {
        /** A plain array read by index, or null. */
        final NativeArray array;

        /** The realm of {@code values} for {@link #array}, which would create the iterator. */
        final TopLevel realm;

        /** The guard of the realm, when it held at the start. */
        final ArrayIterationGuard guard;

        int index;
        Scriptable iterator;
        Object next;
        boolean done;

        /** The value of the last {@link #STEP}. */
        Object value;

        IteratorRecord(NativeArray array, TopLevel realm, ArrayIterationGuard guard) {
            this.array = array;
            this.realm = realm;
            this.guard = guard;
        }
    }

    /**
     * Runs one step: {@code operand} is the value for {@link #OPEN}, which returns the record, and
     * the record for the other steps, which return undefined unless they produce a value.
     */
    public static Object destructure(Object operand, int step, Context cx, VarScope scope) {
        if (step == OPEN) {
            return open(operand, cx, scope);
        }
        if (!(operand instanceof IteratorRecord)) {
            // A handler around the statement before the pattern began.
            return Undefined.instance;
        }
        IteratorRecord record = (IteratorRecord) operand;
        switch (step) {
            case STEP:
                return record.value = step(record, true, cx, scope);
            case VALUE:
                return record.value;
            case SKIP:
                step(record, false, cx, scope);
                return Undefined.instance;
            case REST:
                return rest(record, cx, scope);
            case FINISH:
            case CLOSE_RETURN:
                if (!record.done) {
                    record.done = true;
                    close(record, cx, scope);
                }
                return Undefined.instance;
            case CLOSE_QUIET:
                if (!record.done) {
                    record.done = true;
                    try {
                        close(record, cx, scope);
                    } catch (RhinoException e) {
                        // The exception of the statement wins (IteratorClose, step 5).
                    }
                }
                return Undefined.instance;
            default:
                throw Kit.codeBug();
        }
    }

    private static IteratorRecord open(Object value, Context cx, VarScope scope) {
        if (value != null && value.getClass() == NativeArray.class) {
            NativeArray array = (NativeArray) value;
            ArrayIterationGuard guard = ArrayIterationGuard.of(array);
            if (guard != null && array.getMap().query(SymbolKey.ITERATOR, 0) == null) {
                return new IteratorRecord(array, guard.realm, guard);
            }
            TopLevel realm = ArraySpread.iterationRealm(array);
            if (realm != null) {
                if (guard == null && !ArraySpread.mayHaveReturn(realm)) {
                    ArrayIterationGuard.install(array, realm);
                }
                return new IteratorRecord(array, realm, null);
            }
        }
        Object iterator = ScriptRuntime.callIterator(value, cx, scope);
        if (!(iterator instanceof Scriptable) || iterator instanceof Symbol) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(iterator));
        }
        IteratorRecord record = new IteratorRecord(null, null, null);
        record.iterator = (Scriptable) iterator;
        record.next =
                ScriptRuntime.getObjectPropNoWarn(iterator, ES6Iterator.NEXT_METHOD, cx, scope);
        return record;
    }

    /** IteratorStep, and the value when {@code value} is true; undefined once done. */
    private static Object step(IteratorRecord record, boolean value, Context cx, VarScope scope) {
        if (record.done) {
            return Undefined.instance;
        }
        if (record.array != null) {
            if (record.index >= record.array.getLength()) {
                record.done = true;
                return Undefined.instance;
            }
            // The array iterator reads the element also for an elision.
            Object element = ScriptableObject.getProperty(record.array, record.index++);
            return element == Scriptable.NOT_FOUND ? Undefined.instance : element;
        }
        // Done until the step completes: an error of the iterator does not close it.
        record.done = true;
        if (!(record.next instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(
                    record.iterator, record.next, ES6Iterator.NEXT_METHOD);
        }
        Object result =
                ((Callable) record.next).call(cx, scope, record.iterator, ScriptRuntime.emptyArgs);
        if (!(result instanceof Scriptable) || result instanceof Symbol) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(result));
        }
        Object done =
                ScriptRuntime.getObjectPropNoWarn(result, ES6Iterator.DONE_PROPERTY, cx, scope);
        if (ScriptRuntime.toBoolean(done)) {
            return Undefined.instance;
        }
        Object element =
                value
                        ? ScriptRuntime.getObjectPropNoWarn(
                                result, ES6Iterator.VALUE_PROPERTY, cx, scope)
                        : Undefined.instance;
        record.done = false;
        return element;
    }

    private static Object rest(IteratorRecord record, Context cx, VarScope scope) {
        List<Object> values = new ArrayList<>();
        while (true) {
            Object element = step(record, true, cx, scope);
            if (record.done) {
                break;
            }
            values.add(element);
        }
        return cx.newArray(scope, values.toArray());
    }

    /** IteratorClose of an iterator that is not done; the record is marked done already. */
    private static void close(IteratorRecord record, Context cx, VarScope scope) {
        Scriptable iterator = record.iterator;
        if (record.array != null) {
            if ((record.guard != null && record.guard.holds())
                    || !ArraySpread.mayHaveReturn(record.realm)) {
                return;
            }
            NativeArrayIterator created =
                    new NativeArrayIterator(
                            record.realm,
                            record.array,
                            NativeArrayIterator.ARRAY_ITERATOR_TYPE.VALUES);
            created.setIndex(record.index);
            iterator = created;
        }
        Object method =
                ScriptRuntime.getObjectPropNoWarn(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
        if (method == null || Undefined.isUndefined(method)) {
            return;
        }
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(iterator, method, ES6Iterator.RETURN_PROPERTY);
        }
        Object result = ((Callable) method).call(cx, scope, iterator, ScriptRuntime.emptyArgs);
        if (!(result instanceof Scriptable)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(result));
        }
    }
}
