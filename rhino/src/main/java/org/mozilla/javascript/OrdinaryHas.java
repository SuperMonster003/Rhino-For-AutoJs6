/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;

/**
 * AutoJs6: HasProperty along a prototype chain that reaches a Proxy (ROADMAP P6.15, appendix C
 * Q34). Not related to classes.
 *
 * <p>Upstream asked every object of the chain for an own property, a Proxy included: when its has
 * trap answered false the lookup went on with the prototype from its getPrototypeOf trap, and
 * without a has trap only the own properties of its target were asked. A Proxy now answers for the
 * rest of the chain, with its has trap or with HasProperty of its target.
 *
 * <p>Also the name lookup in a {@code with} statement whose object is a Proxy, which upstream did
 * with Get alone (no has trap, and a name the Proxy has with the value undefined was not found).
 */
final class OrdinaryHas {

    private OrdinaryHas() {}

    /** HasProperty(obj, name). */
    static boolean has(Scriptable obj, String name) {
        for (Scriptable o = obj; o != null; o = o.getPrototype()) {
            if (o instanceof NativeProxy) {
                return proxyHas((NativeProxy) o, name);
            }
            if (o.has(name, obj)) {
                return true;
            }
        }
        return false;
    }

    /** HasProperty(obj, index). */
    static boolean has(Scriptable obj, int index) {
        for (Scriptable o = obj; o != null; o = o.getPrototype()) {
            if (o instanceof NativeProxy) {
                return proxyHas((NativeProxy) o, ScriptRuntime.toString(index));
            }
            if (o.has(index, obj)) {
                return true;
            }
        }
        return false;
    }

    /** HasProperty(obj, key). */
    static boolean has(Scriptable obj, Symbol key) {
        for (Scriptable o = obj; o != null; o = o.getPrototype()) {
            if (o instanceof NativeProxy) {
                return proxyHas((NativeProxy) o, key);
            }
            if (ScriptableObject.ensureSymbolScriptable(o).has(key, obj)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The value of {@code name} in the scope of a {@code with} statement, NOT_FOUND without such a
     * binding. A Proxy goes through HasBinding (HasProperty, then {@code @@unscopables}) and
     * GetBindingValue (HasProperty again, then Get), as its traps observe them; other objects go on
     * as before (Get alone, upstream ignores {@code @@unscopables}).
     */
    static Object withValue(Context cx, Scriptable withObj, String name) {
        if (!(withObj instanceof NativeProxy)) {
            return ScriptableObject.getProperty(withObj, name);
        }
        if (!withHas(withObj, name)) {
            return Scriptable.NOT_FOUND;
        }
        if (!has(withObj, name)) {
            if (cx.isStrictMode()) {
                throw ScriptRuntime.notFoundError(null, name);
            }
            return Undefined.instance;
        }
        Object value = ScriptableObject.getProperty(withObj, name);
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    /** HasBinding of the scope of a {@code with} statement (see {@link #withValue}). */
    static boolean withHas(Scriptable withObj, String name) {
        if (!has(withObj, name)) {
            return false;
        }
        if (!(withObj instanceof NativeProxy)) {
            return true;
        }
        Object unscopables = ScriptableObject.getProperty(withObj, SymbolKey.UNSCOPABLES);
        if (!(unscopables instanceof Scriptable) || ScriptRuntime.isSymbol(unscopables)) {
            return true;
        }
        Object blocked = ScriptableObject.getProperty((Scriptable) unscopables, name);
        return blocked == Scriptable.NOT_FOUND || !ScriptRuntime.toBoolean(blocked);
    }

    /** proxy.[[HasProperty]](key), the key being a String or a Symbol (10.5.7). */
    private static boolean proxyHas(NativeProxy proxy, Object key) {
        ScriptableObject target = proxy.getTargetThrowIfRevoked();
        Function trap = proxy.getTrap("has");
        if (trap == null) {
            if (key instanceof Symbol) {
                return has(target, (Symbol) key);
            }
            StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
            return s.stringId == null ? has(target, s.index) : has(target, s.stringId);
        }
        boolean result = ScriptRuntime.toBoolean(proxy.callTrap(trap, new Object[] {target, key}));
        if (!result) {
            DescriptorInfo targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), key);
            if (targetDesc != null
                    && (targetDesc.isConfigurable(false) || !target.isExtensible())) {
                throw ScriptRuntime.typeError(
                        "proxy can't report an existing own property '"
                                + key
                                + "' as non-existent on a non-extensible object");
            }
        }
        return result;
    }
}
