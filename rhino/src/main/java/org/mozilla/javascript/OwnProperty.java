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

/**
 * AutoJs6: the own properties of a Proxy (ROADMAP P6.15, appendix C Q34). Not related to classes.
 *
 * <p>Upstream read only {@code value}, {@code writable}, {@code enumerable} and {@code
 * configurable} from the result of a getOwnPropertyDescriptor trap, so an accessor became a data
 * property, and checked none of the invariants against the target. [[DefineOwnProperty]] of an
 * ordinary object reports false by throwing, which {@code Reflect.defineProperty} turned into false
 * for the invariant errors of a Proxy too, and {@code Object.defineProperty} dropped the false of a
 * Proxy. HasOwnProperty asked a Proxy its has trap instead of [[GetOwnProperty]], and {@code
 * Reflect.ownKeys} put the strings of the ownKeys trap before its symbols.
 *
 * <p>Also the [[DefineOwnProperty]] of the indices of a String object (10.4.3.2), which upstream
 * added as hidden properties instead of comparing with the character.
 */
final class OwnProperty {

    private OwnProperty() {}

    /** Steps 12-18 of proxy.[[GetOwnProperty]] (10.5.5) for a trap result that is an object. */
    static DescriptorInfo result(
            ScriptableObject target, Object trapResult, DescriptorInfo targetDesc) {
        boolean extensibleTarget = target.isExtensible();
        DescriptorInfo resultDesc = complete(toPropertyDescriptor((Scriptable) trapResult));
        if (!compatible(extensibleTarget, resultDesc, targetDesc)) {
            throw ScriptRuntime.typeError("proxy can't report an incompatible property descriptor");
        }
        if (resultDesc.isConfigurable(false)) {
            if (targetDesc == null || targetDesc.isConfigurable(true)) {
                throw ScriptRuntime.typeError(
                        "proxy can't report a non-configurable property that is configurable or"
                                + " missing on the target");
            }
            if (resultDesc.isWritable(false) && targetDesc.isWritable(true)) {
                throw ScriptRuntime.typeError(
                        "proxy can't report a non-configurable, non-writable property that is"
                                + " writable on the target");
            }
        }
        return resultDesc;
    }

    /**
     * o.[[DefineOwnProperty]](key, desc), through the Proxies without a defineProperty trap. The
     * ordinary object at the end reports false by throwing a TypeError: kept with its message when
     * {@code orThrow}, false otherwise.
     */
    static boolean define(
            Context cx, ScriptableObject o, Object key, DescriptorInfo desc, boolean orThrow) {
        while (o instanceof NativeProxy) {
            NativeProxy proxy = (NativeProxy) o;
            ScriptableObject target = proxy.getTargetThrowIfRevoked();
            Function trap = proxy.getTrap("defineProperty");
            if (trap != null) {
                return proxyDefine(cx, proxy, target, trap, key, desc);
            }
            o = target;
        }
        if (orThrow) {
            return o.defineOwnProperty(cx, key, desc);
        }
        if (o instanceof NativeTypedArrayView && TypedArrayKey.isNumeric(key)) {
            return TypedArrayKey.define((NativeTypedArrayView<?>) o, key, desc);
        }
        try {
            return o.defineOwnProperty(cx, key, desc);
        } catch (EcmaError e) {
            if (!"TypeError".equals(e.getName())) {
                throw e;
            }
            return false;
        }
    }

    /**
     * The objects whose {@code Reflect.defineProperty} is {@link #reflect}: an error that is not
     * the refusal has to get through, like the conversion of the value of a TypedArray element.
     */
    static boolean exotic(ScriptableObject o) {
        return o instanceof NativeProxy || o instanceof NativeTypedArrayView;
    }

    /** {@code Reflect.defineProperty} on a Proxy: ToPropertyDescriptor, then its result. */
    static boolean reflect(Context cx, ScriptableObject proxy, Object key, DescriptorInfo desc) {
        ScriptableObject.checkPropertyDefinition(desc);
        return define(cx, proxy, key, desc, false);
    }

    /** {@code Object.defineProperty}: DefinePropertyOrThrow. */
    static void object(Context cx, ScriptableObject o, Object key, DescriptorInfo desc) {
        if (!o.defineOwnProperty(cx, key, desc) && o instanceof NativeProxy) {
            throw ScriptRuntime.typeError("proxy refused to define property '" + key + "'");
        }
    }

    /** HasOwnProperty of a Proxy: its [[GetOwnProperty]]. */
    static boolean has(Context cx, Scriptable proxy, Object property) {
        Object key = ScriptRuntime.isSymbol(property) ? property : ScriptRuntime.toString(property);
        return ((NativeProxy) proxy).getOwnPropertyDescriptor(cx, key) != null;
    }

    /** {@code Reflect.ownKeys} of a Proxy: the keys in the order of [[OwnPropertyKeys]]. */
    static Scriptable keys(Context cx, VarScope scope, Object[] ids) {
        Object[] keys = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) {
            keys[i] = ids[i] instanceof Symbol ? ids[i] : ScriptRuntime.toString(ids[i]);
        }
        return cx.newArray(scope, keys);
    }

    /**
     * [[DefineOwnProperty]] of a String object for an index of its string (10.4.3.2): compatible
     * with the character, which stays as it is, or a TypeError as for an ordinary object. False for
     * the other keys, which go on as before.
     */
    static boolean stringIndex(Context cx, CharSequence string, Object id, DescriptorInfo desc) {
        if (id instanceof Symbol || cx == null || cx.getLanguageVersion() < Context.VERSION_ES6) {
            return false;
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(id);
        if (s.stringId != null || s.index < 0 || s.index >= string.length()) {
            return false;
        }
        String c = String.valueOf(string.charAt(s.index));
        if (ScriptableObject.isTrue(desc.configurable)) {
            throw ScriptRuntime.typeErrorById("msg.change.configurable.false.to.true", id);
        }
        if (desc.hasEnumerable() && !ScriptRuntime.toBoolean(desc.enumerable)) {
            throw ScriptRuntime.typeErrorById("msg.change.enumerable.with.configurable.false", id);
        }
        if (desc.isAccessorDescriptor()) {
            throw ScriptRuntime.typeErrorById(
                    "msg.change.property.data.to.accessor.with.configurable.false", id);
        }
        if (ScriptableObject.isTrue(desc.writable)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.change.writable.false.to.true.with.configurable.false", id);
        }
        if (desc.hasValue() && !ScriptRuntime.same(desc.value, c)) {
            throw ScriptRuntime.typeErrorById("msg.change.value.with.writable.false", id);
        }
        return true;
    }

    /**
     * IsCompatiblePropertyDescriptor (10.1.6.2), comparing with SameValue where upstream's {@link
     * AbstractEcmaObjectOperations#isCompatiblePropertyDescriptor} used {@code Objects.equals}.
     */
    static boolean compatible(boolean extensible, DescriptorInfo desc, DescriptorInfo current) {
        if (current == null) {
            return extensible;
        }
        if (current.isConfigurable(false)) {
            if (ScriptableObject.isTrue(desc.configurable)) {
                return false;
            }
            if (desc.hasEnumerable()
                    && ScriptRuntime.toBoolean(desc.enumerable)
                            != ScriptRuntime.toBoolean(current.enumerable)) {
                return false;
            }
            if (!desc.isGenericDescriptor()
                    && desc.isAccessorDescriptor() != current.isAccessorDescriptor()) {
                return false;
            }
            if (current.isAccessorDescriptor()) {
                if (desc.hasGetter() && !ScriptRuntime.same(desc.getter, field(current.getter))) {
                    return false;
                }
                if (desc.hasSetter() && !ScriptRuntime.same(desc.setter, field(current.setter))) {
                    return false;
                }
            } else if (current.isWritable(false)) {
                if (ScriptableObject.isTrue(desc.writable)) {
                    return false;
                }
                if (desc.hasValue() && !ScriptRuntime.same(desc.value, field(current.value))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Steps 8-17 of proxy.[[DefineOwnProperty]] (10.5.6), upstream's checks. */
    private static boolean proxyDefine(
            Context cx,
            NativeProxy proxy,
            ScriptableObject target,
            Function trap,
            Object key,
            DescriptorInfo desc) {
        Object descObj = desc.toObject(trap.getDeclarationScope());
        if (!ScriptRuntime.toBoolean(proxy.callTrap(trap, new Object[] {target, key, descObj}))) {
            return false;
        }
        DescriptorInfo targetDesc = target.getOwnPropertyDescriptor(cx, key);
        boolean extensibleTarget = target.isExtensible();
        boolean settingConfigFalse =
                desc.hasConfigurable() && !ScriptableObject.isTrue(desc.configurable);
        if (targetDesc == null) {
            if (!extensibleTarget || settingConfigFalse) {
                throw ScriptRuntime.typeError(
                        "proxy can't define an incompatible property descriptor");
            }
        } else if (!compatible(extensibleTarget, desc, targetDesc)
                || (settingConfigFalse && targetDesc.isConfigurable(true))
                || (targetDesc.isDataDescriptor()
                        && targetDesc.isConfigurable(false)
                        && targetDesc.isWritable(true)
                        && desc.hasWritable()
                        && !ScriptableObject.isTrue(desc.writable))) {
            throw ScriptRuntime.typeError("proxy can't define an incompatible property descriptor");
        }
        return true;
    }

    /** ToPropertyDescriptor (6.2.6.5). */
    private static DescriptorInfo toPropertyDescriptor(Scriptable obj) {
        Object nf = Scriptable.NOT_FOUND;
        Object enumerable = has(obj, "enumerable") ? toBoolean(obj, "enumerable") : nf;
        Object configurable = has(obj, "configurable") ? toBoolean(obj, "configurable") : nf;
        Object value = has(obj, "value") ? get(obj, "value") : nf;
        Object writable = has(obj, "writable") ? toBoolean(obj, "writable") : nf;
        Object getter = has(obj, "get") ? accessor(get(obj, "get")) : nf;
        Object setter = has(obj, "set") ? accessor(get(obj, "set")) : nf;
        if ((getter != nf || setter != nf) && (value != nf || writable != nf)) {
            throw ScriptRuntime.typeErrorById("msg.both.data.and.accessor.desc");
        }
        return new DescriptorInfo(enumerable, writable, configurable, getter, setter, value);
    }

    /** CompletePropertyDescriptor (6.2.6.6). */
    private static DescriptorInfo complete(DescriptorInfo desc) {
        Object nf = Scriptable.NOT_FOUND;
        Object u = Undefined.instance;
        boolean accessor = desc.isAccessorDescriptor();
        return new DescriptorInfo(
                desc.hasEnumerable() ? desc.enumerable : Boolean.FALSE,
                accessor ? nf : desc.hasWritable() ? desc.writable : Boolean.FALSE,
                desc.hasConfigurable() ? desc.configurable : Boolean.FALSE,
                accessor ? (desc.hasGetter() ? desc.getter : u) : nf,
                accessor ? (desc.hasSetter() ? desc.setter : u) : nf,
                accessor ? nf : desc.hasValue() ? desc.value : u);
    }

    private static Object field(Object value) {
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    private static boolean has(Scriptable obj, String name) {
        return ScriptableObject.hasProperty(obj, name);
    }

    private static Object get(Scriptable obj, String name) {
        return field(ScriptableObject.getProperty(obj, name));
    }

    private static Boolean toBoolean(Scriptable obj, String name) {
        return ScriptRuntime.toBoolean(get(obj, name));
    }

    private static Object accessor(Object f) {
        if (!(f instanceof Callable) && !Undefined.isUndefined(f)) {
            throw ScriptRuntime.notFunctionError(f);
        }
        return f;
    }
}
