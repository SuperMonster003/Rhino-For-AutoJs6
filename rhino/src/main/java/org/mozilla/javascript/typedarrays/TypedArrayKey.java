/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.typedarrays;

import java.math.BigInteger;
import java.util.Optional;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;

/**
 * AutoJs6: the internal methods of a TypedArray for a CanonicalNumericIndexString key (10.4.5,
 * ROADMAP P6.18, appendix C Q44). Not related to classes.
 *
 * <p>Upstream left "NaN" out of these keys, so it was an ordinary property, and the other keys that
 * are not valid integer indices went on to the prototypes in [[Get]] and [[HasProperty]].
 * [[DefineOwnProperty]] truncated the key and did not report false as an error, [[GetOwnProperty]]
 * knew no elements and [[OwnPropertyKeys]] gave either the elements or the other keys.
 *
 * <p>These keys never have a slot: a valid index is an element, the others are absent.
 */
public final class TypedArrayKey {

    private static final Optional<Double> NAN = Optional.of(Double.NaN);

    private TypedArrayKey() {}

    /**
     * CanonicalNumericIndexString(name). Unlike upstream's {@link
     * ScriptRuntime#canonicalNumericIndexString}, "NaN" is one.
     */
    static Optional<Double> canonical(String name) {
        if (!mayBeCanonical(name)) {
            return Optional.empty();
        }
        return "NaN".equals(name) ? NAN : ScriptRuntime.canonicalNumericIndexString(name);
    }

    /** CanonicalNumericIndexString(name) is not undefined. */
    public static boolean isCanonical(String name) {
        return canonical(name).isPresent();
    }

    /** Whether a property key is one of the keys above. */
    public static boolean isNumeric(Object id) {
        if (ScriptRuntime.isSymbol(id)) {
            return false;
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(id);
        return s.getStringId() == null || isCanonical(s.getStringId());
    }

    /**
     * False for the names that cannot be {@link #isCanonical}, going by the first character the
     * string of a Number can have. Names of ordinary properties skip the conversion to a Number.
     */
    public static boolean mayBeCanonical(String name) {
        if (name.isEmpty()) {
            return false;
        }
        char c = name.charAt(0);
        return c <= '9' ? c >= '0' || c == '-' : c == 'I' || c == 'N';
    }

    /** [[GetOwnProperty]] (10.4.5.1), {@code own} being the result for the slots. */
    static DescriptorInfo descriptor(NativeTypedArrayView<?> ta, Object id, DescriptorInfo own) {
        if (own != null || ScriptRuntime.isSymbol(id)) {
            return own;
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(id);
        if (s.getStringId() != null || ta.checkIndex(s.getIndex())) {
            return null;
        }
        return new DescriptorInfo(true, true, true, ta.js_get(s.getIndex()));
    }

    /**
     * [[DefineOwnProperty]] (10.4.5.3) for an {@link #isNumeric} key, false when it refuses.
     * Converting the value can throw, which {@code Reflect.defineProperty} does not catch.
     */
    public static boolean define(NativeTypedArrayView<?> ta, Object id, DescriptorInfo desc) {
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(id);
        if (s.getStringId() != null
                || ta.checkIndex(s.getIndex())
                || (desc.hasConfigurable() && !ScriptRuntime.toBoolean(desc.configurable))
                || (desc.hasEnumerable() && !ScriptRuntime.toBoolean(desc.enumerable))
                || desc.isAccessorDescriptor()
                || (desc.hasWritable() && !ScriptRuntime.toBoolean(desc.writable))) {
            return false;
        }
        if (desc.hasValue()) {
            ta.js_set(s.getIndex(), desc.value);
        }
        return true;
    }

    /**
     * DefinePropertyOrThrow, the way {@code defineOwnProperty} reports false for every key: the
     * object methods and {@code Object.freeze} throw, as an ordinary object does. False when the
     * key is not {@link #isNumeric}.
     */
    static boolean defineOrThrow(NativeTypedArrayView<?> ta, Object id, DescriptorInfo desc) {
        if (!isNumeric(id)) {
            return false;
        }
        if (!define(ta, id, desc)) {
            throw ScriptRuntime.typeError(
                    "Cannot define property '" + id + "' of a TypedArray with this descriptor");
        }
        return true;
    }

    /** [[OwnPropertyKeys]] (10.4.5.7): the valid integer indices, then the keys of the slots. */
    static Object[] ids(NativeTypedArrayView<?> ta, Object[] own) {
        int length = ta.isTypedArrayOutOfBounds() ? 0 : ta.getLength();
        if (length == 0) {
            return own;
        }
        Object[] ids = new Object[length + own.length];
        for (int i = 0; i < length; i++) {
            ids[i] = Integer.valueOf(i);
        }
        System.arraycopy(own, 0, ids, length, own.length);
        return ids;
    }

    /**
     * ToBigInt (7.1.13). {@link ScriptRuntime#toBigInt(Object)} also takes a Number, as the {@code
     * BigInt} function does.
     */
    static BigInteger toBigInt(Object value) {
        Object prim = ScriptRuntime.toPrimitive(value, ScriptRuntime.NumberClass);
        if (prim instanceof Number && !(prim instanceof BigInteger)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.cant.convert.to.bigint", ScriptRuntime.toString(prim));
        }
        return ScriptRuntime.toBigInt(prim);
    }
}
