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
 * reads {@code next} and {@code return} of the iterator, calls {@code next()} until a result is
 * done, and calls {@code return()} at the end. The array iterator creates every result with {@link
 * ScriptableObject#putProperty}, which finds {@code value} and {@code done} on {@code
 * Object.prototype}, and reads the elements as own properties. No script code runs when all of
 * these find the built-ins, so the elements are copied directly. That needs:
 *
 * <ul>
 *   <li>a dense {@link NativeArray} (no accessor elements) without holes, which take the slow path
 *       so that how the iterator reads them stays upstream's;
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
        if (!array.getDenseOnly()
                || length > Integer.MAX_VALUE
                || array.getMap().query(SymbolKey.ITERATOR, 0) != null) {
            return -1;
        }
        Scriptable proto = array.getPrototype();
        if (proto == null || proto.getClass() != NativeArray.class) {
            return -1;
        }
        Object values = builtin(((NativeArray) proto).getMap().query(SymbolKey.ITERATOR, 0));
        if (!(values instanceof JSFunction)
                || ((JSFunction) values).getDescriptor() != NativeArray.VALUES_DESCRIPTOR) {
            return -1;
        }
        TopLevel top = ScriptableObject.getTopLevelScope(scope);
        if (ScriptableObject.getTopLevelScope(((JSFunction) values).getDeclarationScope()) != top
                || !plainIterator(top)) {
            return -1;
        }
        for (int i = 0; i < length; i++) {
            if (array.get(i, array) == Scriptable.NOT_FOUND) {
                return -1;
            }
        }
        return (int) length;
    }

    private static boolean plainIterator(TopLevel top) {
        Object iteratorProto = top.getAssociatedValue("ArrayIterator");
        Scriptable objectProto = top.getBuiltinPrototype(TopLevel.Builtins.Object);
        if (!(iteratorProto instanceof NativeArrayIterator)
                || !(objectProto instanceof NativeObject)
                || ((Scriptable) iteratorProto).getPrototype() != objectProto
                || objectProto.getPrototype() != null) {
            return false;
        }
        SlotMap<Scriptable> iterator = ((NativeArrayIterator) iteratorProto).getMap();
        Object next = builtin(iterator.query(ES6Iterator.NEXT_METHOD, 0));
        if (!(next instanceof JSFunction)
                || ((JSFunction) next).getDescriptor() != NativeArrayIterator.NEXT_DESCRIPTOR
                || iterator.query(ES6Iterator.RETURN_METHOD, 0) != null) {
            return false;
        }
        SlotMap<Scriptable> object = ((NativeObject) objectProto).getMap();
        return object.query(ES6Iterator.RETURN_METHOD, 0) == null
                && object.query(ES6Iterator.VALUE_PROPERTY, 0) == null
                && object.query(ES6Iterator.DONE_PROPERTY, 0) == null;
    }

    /** The value of a plain data slot, or null. */
    private static Object builtin(Slot<?> slot) {
        return slot != null && slot.getClass() == StandardSlot.class ? slot.value : null;
    }
}
