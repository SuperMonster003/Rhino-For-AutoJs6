/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.Arrays;

/**
 * AutoJs6: copies the elements of an array for a spread, {@code [...a]} and the spread arguments of
 * {@link SpreadCall}, when iterating it can not be observed (ROADMAP P6.9, appendix C Q19). Not
 * related to classes.
 *
 * <p>A spread iterates its value: {@link NewLiteralStorage} calls {@code a[Symbol.iterator]()},
 * reads {@code next} of the iterator and calls {@code next()} until a result is done. The array
 * iterator reads the elements with Get, so holes find index properties of the prototypes and
 * accessor elements run their getters. No script code runs when all of these find the built-ins, so
 * the elements are copied directly. That needs:
 *
 * <ul>
 *   <li>a dense {@link NativeArray} (no accessor elements) without holes, whose reads go to the
 *       prototypes;
 *   <li>no own {@code Symbol.iterator}, and the built-in {@code values} as the own {@code
 *       Symbol.iterator} data property of its prototype, an array (as {@code Array.prototype} is);
 *   <li>the built-in {@code next} as an own data property of {@code %ArrayIteratorPrototype%}, and
 *       no {@code return} on it or on {@code Object.prototype}, its prototype;
 *   <li>no {@code value} and {@code done} on {@code Object.prototype}, which has no prototype;
 *   <li>the realm of the caller as the realm of {@code values}, whose iterator and results it
 *       creates.
 * </ul>
 *
 * The checks read the slots without calling getters, once per spread: a change of any of these,
 * also between two spreads of one literal or call, sends the next spread through the protocol.
 *
 * <p>The checks of {@code return}, {@code value} and {@code done} date from before P6.10, when
 * upstream looked up {@code return} at the start and called it at the end, and wrote the results
 * with {@link ScriptableObject#putProperty}, which found these on {@code Object.prototype}
 * (appendix C Q21 / Q22). None of them is observable by a spread any more; they stay as a
 * conservative part of the conditions.
 */
final class ArraySpread {

    private ArraySpread() {}

    /**
     * Copies the elements of the source into the storage of an array literal and returns true, or
     * returns false when the source must be iterated.
     */
    static boolean spread(NewLiteralStorage storage, Object source, VarScope scope) {
        int length = plainLength(source, scope);
        if (length < 0) {
            return false;
        }
        NativeArray array = (NativeArray) source;
        int newLength = storage.values.length + length;
        storage.getterSetters = Arrays.copyOf(storage.getterSetters, newLength);
        storage.values = Arrays.copyOf(storage.values, newLength);
        for (int i = 0; i < length; i++) {
            storage.values[storage.index++] = array.get(i, array);
        }
        return true;
    }

    /** The elements of the source, or null when the source must be iterated. */
    static Object[] elements(Object source, VarScope scope) {
        int length = plainLength(source, scope);
        if (length < 0) {
            return null;
        }
        if (length == 0) {
            return ScriptRuntime.emptyArgs;
        }
        NativeArray array = (NativeArray) source;
        Object[] elements = new Object[length];
        for (int i = 0; i < length; i++) {
            elements[i] = array.get(i, array);
        }
        return elements;
    }

    /** The length of the source when spreading it can not be observed, else -1. */
    private static int plainLength(Object source, VarScope scope) {
        if (source == null || source.getClass() != NativeArray.class) {
            return -1;
        }
        NativeArray array = (NativeArray) source;
        long length = array.getLength();
        if (!array.getDenseOnly() || length > Integer.MAX_VALUE) {
            return -1;
        }
        TopLevel top = ScriptableObject.getTopLevelScope(scope);
        if (iterationRealm(array) != top || mayHaveReturn(top)) {
            return -1;
        }
        SlotMap<Scriptable> object =
                ((NativeObject) top.getBuiltinPrototype(TopLevel.Builtins.Object)).getMap();
        if (object.query(ES6Iterator.VALUE_PROPERTY, 0) != null
                || object.query(ES6Iterator.DONE_PROPERTY, 0) != null) {
            return -1;
        }
        for (int i = 0; i < length; i++) {
            if (array.get(i, array) == Scriptable.NOT_FOUND) {
                return -1;
            }
        }
        return (int) length;
    }

    /**
     * The realm of the built-in {@code values} that iterates the source, a {@link NativeArray}
     * whose iteration up to the results can not be observed: no own {@code Symbol.iterator}, {@code
     * values} as the own one of its prototype, an array, and the built-in {@code next} of the
     * {@code %ArrayIteratorPrototype%} of that realm. Else null. Also used by {@link
     * ArrayDestructuring} (P6.12), which reads by index whatever the elements are.
     */
    static TopLevel iterationRealm(Object source) {
        if (source == null || source.getClass() != NativeArray.class) {
            return null;
        }
        NativeArray array = (NativeArray) source;
        if (array.getMap().query(SymbolKey.ITERATOR, 0) != null) {
            return null;
        }
        Scriptable proto = array.getPrototype();
        if (proto == null || proto.getClass() != NativeArray.class) {
            return null;
        }
        Object values = builtin(((NativeArray) proto).getMap().query(SymbolKey.ITERATOR, 0));
        if (!(values instanceof JSFunction)
                || ((JSFunction) values).getDescriptor() != NativeArray.VALUES_DESCRIPTOR) {
            return null;
        }
        TopLevel top =
                ScriptableObject.getTopLevelScope(((JSFunction) values).getDeclarationScope());
        Object iteratorProto = top.getAssociatedValue("ArrayIterator");
        if (!(iteratorProto instanceof NativeArrayIterator)) {
            return null;
        }
        Object next =
                builtin(
                        ((NativeArrayIterator) iteratorProto)
                                .getMap()
                                .query(ES6Iterator.NEXT_METHOD, 0));
        return next instanceof JSFunction
                        && ((JSFunction) next).getDescriptor()
                                == NativeArrayIterator.NEXT_DESCRIPTOR
                ? top
                : null;
    }

    /**
     * Whether an array iterator of the realm may find a {@code return} method: unless {@code
     * %ArrayIteratorPrototype%} and {@code Object.prototype}, its prototype, which has none, hold
     * none.
     */
    static boolean mayHaveReturn(TopLevel top) {
        Object iteratorProto = top.getAssociatedValue("ArrayIterator");
        Scriptable objectProto = top.getBuiltinPrototype(TopLevel.Builtins.Object);
        return !(iteratorProto instanceof NativeArrayIterator)
                || !(objectProto instanceof NativeObject)
                || ((Scriptable) iteratorProto).getPrototype() != objectProto
                || objectProto.getPrototype() != null
                || ((NativeArrayIterator) iteratorProto)
                                .getMap()
                                .query(ES6Iterator.RETURN_METHOD, 0)
                        != null
                || ((NativeObject) objectProto).getMap().query(ES6Iterator.RETURN_METHOD, 0)
                        != null;
    }

    /** The value of a plain data slot, or null. */
    private static Object builtin(Slot<?> slot) {
        return slot != null && slot.getClass() == StandardSlot.class ? slot.value : null;
    }
}
