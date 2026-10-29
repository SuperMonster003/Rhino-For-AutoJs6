/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: GetSetRecord for the set-like argument of the {@link NativeSet} methods (ROADMAP P6.13,
 * appendix C Q26). Not related to classes.
 *
 * <p>Upstream read {@code size}, {@code has} and {@code keys} first and converted and checked them
 * afterwards, so a getter of {@code has} ran before {@code size} was converted, and a negative size
 * was accepted. The spec finishes each one before reading the next: {@code size} (ToNumber, NaN is
 * a TypeError, ToIntegerOrInfinity, negative is a RangeError), then {@code has}, then {@code keys}
 * (each must be callable). The methods read them through {@link #size} and {@link #method} in that
 * order, so their later checks always pass.
 */
final class SetRecord {

    private SetRecord() {}

    /** The {@code size} of the record: an integer or an infinity, never negative. */
    static Object size(Scriptable obj) {
        double num = ScriptRuntime.toNumber(get(obj, "size", " property"));
        if (Double.isNaN(num)) {
            throw ScriptRuntime.typeError("size is not a number");
        }
        double size = ScriptRuntime.toIntegerOrInfinity(num);
        if (size < 0) {
            throw ScriptRuntime.rangeError("size must not be negative");
        }
        return size;
    }

    /** The {@code has} or {@code keys} of the record; a TypeError when it is not callable. */
    static Object method(Scriptable obj, String name) {
        Object value = get(obj, name, " method");
        if (!(value instanceof Callable)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.isnt.function", name, ScriptRuntime.typeof(value));
        }
        return value;
    }

    /** CanonicalizeKeyedCollectionKey: -0 is +0. */
    static Object canonicalKey(Object key) {
        if (key instanceof Number && ((Number) key).doubleValue() == ScriptRuntime.negativeZero) {
            return ScriptRuntime.zeroObj;
        }
        return key;
    }

    /** A missing property is undefined, a TypeError with upstream's message. */
    private static Object get(Scriptable obj, String name, String kind) {
        Object value = ScriptableObject.getProperty(obj, name);
        if (value == Scriptable.NOT_FOUND) {
            throw ScriptRuntime.typeError("Set-like object must have a '" + name + "'" + kind);
        }
        return value;
    }
}
