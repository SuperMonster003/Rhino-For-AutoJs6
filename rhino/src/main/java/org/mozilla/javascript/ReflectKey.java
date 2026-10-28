/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.typedarrays.NativeTypedArrayView;

/**
 * AutoJs6: the property key of the {@link NativeReflect} methods (ROADMAP P6.13, appendix C Q27).
 * Not related to classes.
 *
 * <p>Upstream looked up a string key by name only, so an index such as {@code '0'} (stored by
 * index) was not found by {@code Reflect.get}, {@code has} and {@code deleteProperty}, and a Proxy
 * trap forwarding its (string) key through {@code Reflect.get} read no array element. {@link #of}
 * is ToPropertyKey (once, before anything else the method converts), and the lookups dispatch the
 * key to the index or the name the way property access does. A missing value is {@link
 * Undefined#instance}, not the scriptable undefined whose {@code typeof} is "object", and {@code
 * deleteProperty} deletes a property of the target only.
 */
final class ReflectKey {

    private ReflectKey() {}

    /** ToPropertyKey of {@code args[index]}: a symbol or a string. */
    static Object of(Object[] args, int index) {
        Object key = index < args.length ? args[index] : Undefined.instance;
        if (key instanceof Scriptable && !ScriptRuntime.isSymbol(key)) {
            key = ScriptRuntime.toPrimitive(key, ScriptRuntime.StringClass);
        }
        return ScriptRuntime.isSymbol(key) ? key : ScriptRuntime.toString(key);
    }

    /** target.[[Get]](key, receiver). */
    static Object get(Scriptable target, Scriptable receiver, Object key) {
        Object value;
        if (key instanceof Symbol) {
            value = ScriptableObject.getSuperProperty(target, receiver, (Symbol) key);
        } else {
            StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
            value =
                    s.stringId == null
                            ? ScriptableObject.getSuperProperty(target, receiver, s.index)
                            : ScriptableObject.getSuperProperty(target, receiver, s.stringId);
        }
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    /**
     * target.[[HasProperty]](key). An index of a typed array is only its own (IsValidIntegerIndex),
     * never one of its prototypes.
     */
    static boolean has(Scriptable target, Object key) {
        if (key instanceof Symbol) {
            return ScriptableObject.hasProperty(target, (Symbol) key);
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
        if (s.stringId != null) {
            return ScriptableObject.hasProperty(target, s.stringId);
        }
        return target instanceof NativeTypedArrayView
                ? target.has(s.index, target)
                : ScriptableObject.hasProperty(target, s.index);
    }

    /**
     * target.[[Delete]](key): the target deletes its own property (a Proxy calls its trap whether
     * the property exists or not). Upstream's {@link ScriptableObject#deleteProperty} deleted it
     * from the prototype that held it.
     */
    static boolean delete(Scriptable target, Object key) {
        if (key instanceof Symbol) {
            Symbol symbol = (Symbol) key;
            SymbolScriptable obj = ScriptableObject.ensureSymbolScriptable(target);
            obj.delete(symbol);
            return !obj.has(symbol, target);
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
        if (s.stringId == null) {
            target.delete(s.index);
            return !target.has(s.index, target);
        }
        target.delete(s.stringId);
        return !target.has(s.stringId, target);
    }
}
