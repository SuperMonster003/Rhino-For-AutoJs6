/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;
import org.mozilla.javascript.typedarrays.NativeTypedArrayView;
import org.mozilla.javascript.typedarrays.TypedArrayKey;
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
 * assignment or of {@code typeof} looked the name up again after {@code bind}. Strict mode code
 * also checks that a binding of the global scope is still there before writing it (ROADMAP P6.17,
 * appendix C Q41).
 */
final class OrdinaryHas {

    /** Whether a Proxy was created. */
    static volatile boolean proxies;

    /** A bit of the mask of a name increment or decrement that no {@link Node} flag uses. */
    private static final int STRICT_INCR_DECR = 1 << 30;

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
            return found(obj, ScriptableObject.getBase(obj, name), name);
        }
        for (Scriptable o = obj; o != null; o = o.getPrototype()) {
            if (o instanceof NativeProxy) {
                return proxyHas((NativeProxy) o, name);
            }
            if (o.has(name, obj)) {
                return true;
            }
            if (o instanceof NativeTypedArrayView && TypedArrayKey.isCanonical(name)) {
                return false;
            }
        }
        return false;
    }

    /** HasProperty(obj, index). */
    static boolean has(Scriptable obj, int index) {
        if (!proxies) {
            return found(obj, ScriptableObject.getBase(obj, index));
        }
        for (Scriptable o = obj; o != null; o = o.getPrototype()) {
            if (o instanceof NativeProxy) {
                return proxyHas((NativeProxy) o, ScriptRuntime.toString(index));
            }
            if (o.has(index, obj)) {
                return true;
            }
            if (o instanceof NativeTypedArrayView) {
                return false;
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
     * HasProperty from the object that {@code getBase} found: a TypedArray on the way there answers
     * for a CanonicalNumericIndexString itself, without its prototypes (10.4.5.2, ROADMAP P6.18,
     * appendix C Q44). The chain is only walked for a key found on a prototype that may be one.
     */
    static boolean found(Scriptable obj, Scriptable base, String name) {
        return base == obj
                || (base != null
                        && !(TypedArrayKey.mayBeCanonical(name)
                                && typedArrayBefore(obj, base)
                                && TypedArrayKey.isCanonical(name)));
    }

    /** {@link #found(Scriptable, Scriptable, String)} for an index. */
    static boolean found(Scriptable obj, Scriptable base) {
        return base == obj || (base != null && !typedArrayBefore(obj, base));
    }

    private static boolean typedArrayBefore(Scriptable obj, Scriptable base) {
        for (Scriptable o = obj; o != base && o != null; o = o.getPrototype()) {
            if (o instanceof NativeTypedArrayView) {
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
     * The value of a name that {@code bind} resolved, for a compound assignment (ROADMAP P6.16,
     * appendix C Q37, see {@link ClassOp#markBoundRead}): GetBindingValue of the scope it found,
     * without resolving the name again. An unresolvable name is a ReferenceError; an E4X scope, a
     * scope that does not give the value, and NOT_FOUND from {@link #logicalBind} look the name up
     * as before.
     */
    static Object boundValue(Object bound, Context cx, VarScope scope, String name) {
        if (bound == Scriptable.NOT_FOUND) {
            return ScriptRuntime.name(cx, scope, name);
        }
        if (bound instanceof WithScope) {
            Scriptable withObj = ((WithScope) bound).getObject();
            if (!(withObj instanceof XMLObject)) {
                return bindingValue(cx, withObj, name);
            }
        } else if (bound instanceof VarScope) {
            Object value = ((VarScope) bound).get(name, (VarScope) bound);
            if (value != Scriptable.NOT_FOUND) {
                return value;
            }
        } else if (bound == null) {
            throw ScriptRuntime.notFoundError(scope, name);
        }
        return ScriptRuntime.name(cx, scope, name);
    }

    /**
     * The binding of a logical assignment to a name (ROADMAP P6.17, appendix C Q42, see {@link
     * ClassOp#LOGICAL_NAME}): the scope that {@code bind} finds, or NOT_FOUND when no {@code with}
     * scope is on the chain. Without one, resolving the name for the read and again for the write
     * finds the same binding (only declarative scopes and the global scope are left), and the read
     * and the write look the name up as before ({@link #boundValue}, {@link #logicalTarget}).
     */
    static Object logicalBind(Context cx, VarScope scope, String name) {
        if (scope instanceof WithScope) {
            // the assignment is directly in the with statement: HasBinding of its object
            Scriptable withObj = ((WithScope) scope).getObject();
            if (!(withObj instanceof XMLObject)) {
                return withHas(withObj, name)
                        ? scope
                        : ScriptRuntime.bind(cx, scope.getParentScope(), name);
            }
        }
        for (VarScope s = scope; s != null; s = s.getParentScope()) {
            if (s instanceof WithScope) {
                return ScriptRuntime.bind(cx, scope, name);
            }
        }
        return Scriptable.NOT_FOUND;
    }

    /** The scope that the write of a logical assignment goes to, see {@link #logicalBind}. */
    static VarScope logicalTarget(Object bound, Context cx, VarScope scope, String name) {
        return bound == Scriptable.NOT_FOUND
                ? ScriptRuntime.bind(cx, scope, name)
                : (VarScope) bound;
    }

    /** The value that {@code typeof name} looks at, once {@code bind} found {@code bound}. */
    static Object typeofValue(VarScope bound, Context cx, String name) {
        if (bound instanceof WithScope) {
            Scriptable withObj = ((WithScope) bound).getObject();
            if (!(withObj instanceof XMLObject)) {
                return bindingValue(cx, withObj, name);
            }
        }
        return bound.get(name, bound);
    }

    /**
     * SetMutableBinding in strict mode code of the scope of a {@code with} statement and of the
     * global scope (ROADMAP P6.17, appendix C Q41), which {@code bound} is when this returns true:
     * ReferenceError when the binding is gone, as the right side, a getter of {@code @@unscopables}
     * or of the old value of a compound assignment can delete it.
     */
    static boolean strictSet(VarScope bound, String name, Object value) {
        if (bound instanceof TopLevel) {
            return strictGlobalSet((TopLevel) bound, name, value);
        }
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

    /**
     * The mask of a name increment or decrement, with {@link #STRICT_INCR_DECR} in strict mode
     * code: the put comes after the old value was converted, where the context is not at hand.
     */
    static int incrDecrMask(Context cx, int mask) {
        return cx.isStrictMode() ? mask | STRICT_INCR_DECR : mask;
    }

    /**
     * The put of a name increment or decrement: a {@code with} scope or the global scope in strict
     * mode code goes through {@link #strictSet}, other scopes put as before.
     */
    static void incrDecrPut(VarScope target, String name, Object value, int mask) {
        if (!((mask & STRICT_INCR_DECR) != 0 && strictSet(target, name, value))) {
            target.put(name, target, value);
        }
    }

    /**
     * The put of {@link TopLevel}, a lexical binding of the global scope or else the global object,
     * which first asks the HasProperty of the object record: the object whose put handles the
     * assignment is the one that has the property, so nothing is looked up twice.
     */
    private static boolean strictGlobalSet(TopLevel top, String name, Object value) {
        if (top.getMap().query(name, 0) != null) {
            return false;
        }
        ScriptableObject global = top.getGlobalThis();
        Scriptable base = OrdinarySet.base(global, name);
        if (base == null || (base instanceof NativeProxy && !has(global, name))) {
            throw ScriptRuntime.notFoundError(null, name);
        }
        base.put(name, global, value);
        return true;
    }

    /** GetBindingValue of the scope of a {@code with} statement. */
    private static Object bindingValue(Context cx, Scriptable withObj, String name) {
        if (!proxies) {
            // without a Proxy nothing observes HasProperty: a value found by Get is the binding's
            Object value = ScriptableObject.getProperty(withObj, name);
            if (value != Scriptable.NOT_FOUND) {
                return value;
            }
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
