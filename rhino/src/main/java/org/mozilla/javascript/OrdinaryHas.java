/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;
import org.mozilla.javascript.xml.XMLObject;

/**
 * AutoJs6: HasProperty along a prototype chain that reaches a Proxy (ROADMAP P6.15, appendix C
 * Q34). Not related to classes.
 *
 * <p>Upstream asked every object of the chain for an own property, a Proxy included: when its has
 * trap answered false the lookup went on with the prototype from its getPrototypeOf trap, and
 * without a has trap only the own properties of its target were asked. A Proxy now answers for the
 * rest of the chain, with its has trap or with HasProperty of its target.
 *
 * <p>Until a Proxy is created no chain can reach one, and upstream's loop ({@code getBase}) is
 * kept: a check for a Proxy in that loop made {@code in} along a prototype chain up to twice as
 * slow in compiled code (ROADMAP P6.15 timing).
 *
 * <p>Also the bindings of a {@code with} statement (ROADMAP P6.15 / P6.16, appendix C Q34 / Q36 /
 * Q37), which upstream looked up with Get alone: no has trap, a name the Proxy has with the value
 * undefined was not found, {@code @@unscopables} was not read, and the read of a compound
 * assignment or of {@code typeof} looked the name up again after {@code bind}.
 */
final class OrdinaryHas {

    /** Whether a Proxy was created. */
    static volatile boolean proxies;

    private OrdinaryHas() {}

    /** Called by the constructor of a Proxy. */
    static void created() {
        if (!proxies) {
            proxies = true;
        }
    }

    /** HasProperty(obj, name). */
    static boolean has(Scriptable obj, String name) {
        if (!proxies) {
            return ScriptableObject.getBase(obj, name) != null;
        }
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
        if (!proxies) {
            return ScriptableObject.getBase(obj, index) != null;
        }
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
        if (!proxies) {
            return ScriptableObject.getBase(obj, key) != null;
        }
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
     * binding: HasBinding (HasProperty, then {@code @@unscopables}) and GetBindingValue
     * (HasProperty again, then Get), as getters and the traps of a Proxy observe them. Without a
     * Proxy and without {@code @@unscopables} on the prototype chain nothing can observe the steps,
     * and the value is read once as before.
     */
    static Object withValue(Context cx, Scriptable withObj, String name) {
        if (proxies) {
            return withHas(withObj, name) ? bindingValue(cx, withObj, name) : Scriptable.NOT_FOUND;
        }
        Scriptable base = ScriptableObject.getBase(withObj, name);
        if (base == null) {
            return Scriptable.NOT_FOUND;
        }
        Object unscopables = unscopables(withObj);
        if (unscopables == Scriptable.NOT_FOUND) {
            return base.get(name, withObj);
        }
        if (blocked(unscopables, name)) {
            return Scriptable.NOT_FOUND;
        }
        return bindingValue(cx, withObj, name);
    }

    /** HasBinding of the scope of a {@code with} statement (see {@link #withValue}). */
    static boolean withHas(Scriptable withObj, String name) {
        if (!has(withObj, name)) {
            return false;
        }
        Object unscopables = unscopables(withObj);
        return unscopables == Scriptable.NOT_FOUND || !blocked(unscopables, name);
    }

    /**
     * SetMutableBinding of the scope of a {@code with} statement in strict mode code, which {@code
     * bound} is when this returns true: ReferenceError when the binding is gone, as a getter of
     * {@code @@unscopables} or of the old value of a compound assignment can delete it.
     */
    static boolean strictSet(VarScope bound, String name, Object value) {
        if (!(bound instanceof WithScope)) {
            return false;
        }
        Scriptable withObj = ((WithScope) bound).getObject();
        if (withObj instanceof XMLObject) {
            return false;
        }
        if (!has(withObj, name)) {
            throw ScriptRuntime.notFoundError(null, name);
        }
        ScriptableObject.putProperty(withObj, name, value);
        return true;
    }

    /** GetBindingValue of the scope of a {@code with} statement. */
    private static Object bindingValue(Context cx, Scriptable withObj, String name) {
        if (!has(withObj, name)) {
            if (cx.isStrictMode()) {
                throw ScriptRuntime.notFoundError(null, name);
            }
            return Undefined.instance;
        }
        Object value = ScriptableObject.getProperty(withObj, name);
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    /**
     * Get(withObj, @@unscopables), or NOT_FOUND when no object of the prototype chain has that
     * property and none is a Proxy: then nothing was read.
     */
    private static Object unscopables(Scriptable withObj) {
        for (Scriptable o = withObj; o != null; o = o.getPrototype()) {
            Object value;
            if (o instanceof NativeProxy) {
                value = ScriptableObject.getProperty(withObj, SymbolKey.UNSCOPABLES);
            } else if (!(o instanceof SymbolScriptable)) {
                break;
            } else if (((SymbolScriptable) o).has(SymbolKey.UNSCOPABLES, withObj)) {
                value = ((SymbolScriptable) o).get(SymbolKey.UNSCOPABLES, withObj);
            } else {
                continue;
            }
            return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
        }
        return Scriptable.NOT_FOUND;
    }

    /** Whether the {@code @@unscopables} object blocks {@code name}. */
    private static boolean blocked(Object unscopables, String name) {
        if (!(unscopables instanceof Scriptable)
                || Undefined.isUndefined(unscopables)
                || ScriptRuntime.isSymbol(unscopables)) {
            return false;
        }
        Object blocked = ScriptableObject.getProperty((Scriptable) unscopables, name);
        return blocked != Scriptable.NOT_FOUND && ScriptRuntime.toBoolean(blocked);
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
