/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;
import org.mozilla.javascript.typedarrays.NativeTypedArrayView;

/**
 * AutoJs6: [[Set]] with its result, for {@code Reflect.set}, the put of a Proxy and an assignment
 * with a Proxy in the prototype chain (ROADMAP P6.14, appendix C Q33). Not related to classes.
 *
 * <p>Upstream's {@code Reflect.set} with a receiver other than the target asked a prototype without
 * the property to put the value, and {@link ScriptableObject#put} passed that on to the put of the
 * receiver: a Proxy whose set trap forwards with {@code Reflect.set(t, k, v, r)} called its trap
 * again until the stack overflowed. {@code Reflect.set} also returned true whatever happened and
 * did not look at the prototypes of the target, a Proxy without a set trap replaced the receiver by
 * its target, and an assignment asked the has trap of a Proxy in the prototype chain instead of
 * calling its set trap.
 *
 * <p>{@link #set} is OrdinarySet: it looks for the own property along the prototype chain and hands
 * the receiver to the first Proxy on the way. A data property ends at the receiver: through its
 * [[GetOwnProperty]] and [[DefineOwnProperty]] when it is a Proxy, otherwise with put as before
 * (dense arrays, Java backed slots) once the checks that make the result false have passed. An
 * accessor is written with put on its holder, which calls the setter with the receiver.
 *
 * <p>A TypedArray handles a CanonicalNumericIndexString itself, without asking its prototypes
 * (ROADMAP P6.17, appendix C Q43): with another receiver an invalid index is not written and a
 * valid one is written to the receiver. Upstream wrote a valid index to the TypedArray and created
 * the other keys on the receiver.
 */
final class OrdinarySet {

    private OrdinarySet() {}

    /**
     * The object whose put handles an assignment: the first that has the property, a Proxy, or a
     * TypedArray with a CanonicalNumericIndexString (see {@link #typedArrayPut}).
     */
    static Scriptable base(Scriptable obj, String name) {
        Scriptable o = obj;
        do {
            if (o instanceof NativeProxy
                    || o.has(name, obj)
                    || (o instanceof NativeTypedArrayView && canonicalNumeric(name))) {
                return o;
            }
            o = o.getPrototype();
        } while (o != null);
        return null;
    }

    /**
     * The object whose put handles an assignment: the first that has the property, a Proxy, or a
     * TypedArray (see {@link #typedArrayPut}).
     */
    static Scriptable base(Scriptable obj, int index) {
        Scriptable o = obj;
        do {
            if (o instanceof NativeProxy
                    || o.has(index, obj)
                    || o instanceof NativeTypedArrayView) {
                return o;
            }
            o = o.getPrototype();
        } while (o != null);
        return null;
    }

    /** The object whose put handles an assignment: the first that has the property, or a Proxy. */
    static Scriptable base(Scriptable obj, Symbol key) {
        Scriptable o = obj;
        do {
            if (o instanceof NativeProxy
                    || ScriptableObject.ensureSymbolScriptable(o).has(key, obj)) {
                return o;
            }
            o = o.getPrototype();
        } while (o != null);
        return null;
    }

    /** The put of a Proxy: its [[Set]] with {@code start} as the receiver. */
    static void put(NativeProxy proxy, Object key, Scriptable start, Object value) {
        if (!proxySet(Context.getContext(), proxy, key, value, start)
                && Context.isCurrentContextStrict()) {
            throw ScriptRuntime.typeError("proxy refused to set property '" + key + "'");
        }
    }

    /** o.[[Set]](key, value, receiver), the key being a String or a Symbol. */
    static boolean set(Context cx, Scriptable o, Object key, Object value, Object receiver) {
        Scriptable holder = o;
        DescriptorInfo ownDesc = null;
        for (; holder != null; holder = holder.getPrototype()) {
            if (holder instanceof NativeProxy) {
                return proxySet(cx, (NativeProxy) holder, key, value, receiver);
            }
            if (holder instanceof NativeTypedArrayView && !(key instanceof Symbol)) {
                StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
                if (s.stringId == null) {
                    if (holder == receiver) {
                        holder.put(s.index, holder, value);
                        return true;
                    }
                    if (!holder.has(s.index, holder)) {
                        return true;
                    }
                    ownDesc = new DescriptorInfo(true, true, true, holder.get(s.index, holder));
                    holder = null;
                    break;
                }
                if (canonicalNumeric(s.stringId)) {
                    // not a valid integer index: nothing is written, the prototypes are not asked
                    if (holder == receiver) {
                        holder.put(s.stringId, holder, value);
                    }
                    return true;
                }
            }
            if (holder instanceof ScriptableObject) {
                ownDesc = ((ScriptableObject) holder).getOwnPropertyDescriptor(cx, key);
                if (ownDesc != null) {
                    break;
                }
            } else if (has(holder, key)) {
                if (!isObject(receiver)) {
                    return false;
                }
                write(holder, key, (Scriptable) receiver, value);
                return true;
            }
        }

        if (ownDesc != null && ownDesc.isAccessorDescriptor()) {
            if (!(ownDesc.setter instanceof Function)) {
                return false;
            }
            if (isObject(receiver)) {
                write(holder, key, (Scriptable) receiver, value);
            } else {
                Function setter = (Function) ownDesc.setter;
                VarScope scope = setter.getDeclarationScope();
                // the setters of Java backed slots (__proto__) take an object as this
                Object thisObj =
                        setter instanceof LambdaFunction
                                ? ScriptRuntime.toObject(cx, scope, receiver)
                                : receiver;
                setter.call(cx, scope, thisObj, new Object[] {value});
            }
            return true;
        }
        if (ownDesc != null && ownDesc.isWritable(false)) {
            return false;
        }
        if (!isObject(receiver)) {
            return false;
        }

        Scriptable r = (Scriptable) receiver;
        if (r instanceof NativeProxy) {
            DescriptorInfo existing = ((NativeProxy) r).getOwnPropertyDescriptor(cx, key);
            if (existing != null
                    && (existing.isAccessorDescriptor() || existing.isWritable(false))) {
                return false;
            }
            return define(cx, r, key, value, existing == null);
        }
        if (r instanceof NativeTypedArrayView && !(key instanceof Symbol)) {
            // its [[DefineOwnProperty]] writes an element, and is false for an invalid index
            StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
            if (s.stringId == null) {
                if (!r.has(s.index, r)) {
                    return false;
                }
                r.put(s.index, r, value);
                return true;
            }
            if (canonicalNumeric(s.stringId)) {
                return false;
            }
        }
        if (r != holder && r instanceof ScriptableObject) {
            ScriptableObject obj = (ScriptableObject) r;
            DescriptorInfo existing = obj.getOwnPropertyDescriptor(cx, key);
            if (existing == null
                    ? !obj.isExtensible()
                    : existing.isAccessorDescriptor() || existing.isWritable(false)) {
                return false;
            }
        }
        write(holder == null ? r : holder, key, r, value);
        return true;
    }

    /**
     * PutValue with a primitive base: ToObject(base).[[Set]](key, value, base), a TypeError when it
     * is false in strict code. Upstream threw for every primitive base in strict code (ES5), so a
     * setter or a Proxy on the prototype of a primitive was not called, and elsewhere put the value
     * on the wrapper, which a setter got as this. False for the bases that go on as before.
     */
    static boolean primitive(Context cx, VarScope scope, Object base, Object key, Object value) {
        if (base instanceof ScriptableObject
                || !(base instanceof CharSequence
                        || base instanceof Number
                        || base instanceof Boolean
                        || base instanceof Symbol)
                || cx.getLanguageVersion() < Context.VERSION_ES6) {
            return false;
        }
        Object k = ReflectKey.of(new Object[] {key}, 0);
        if (!set(cx, ScriptRuntime.toObject(cx, scope, base), k, value, base)
                && cx.isStrictMode()) {
            // undefWriteError cannot convert a symbol to a string
            throw base instanceof Symbol
                    ? ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict")
                    : ScriptRuntime.undefWriteError(base, key, value);
        }
        return true;
    }

    /**
     * SetMutableBinding of a {@code with} object asks HasProperty before the Set. Upstream's
     * assignment did so for a Proxy through the lookup of {@code putProperty}, which {@link #base}
     * no longer does; other objects are not asked (the answer is not observable).
     */
    static void binding(Scriptable withObj, String id) {
        if (withObj instanceof NativeProxy) {
            withObj.has(id, withObj);
        }
    }

    /**
     * HasProperty, then Get: the value at {@code index}, or NOT_FOUND when absent. For {@code
     * Array.prototype.copyWithin}, which treated a value of undefined as absent and relied on the
     * assignment asking the has trap of a Proxy (no longer asked since P6.14).
     */
    static Object getIfPresent(Scriptable o, long index) {
        boolean present =
                index > Integer.MAX_VALUE
                        ? ScriptableObject.hasProperty(o, Long.toString(index))
                        : ScriptableObject.hasProperty(o, (int) index);
        return present ? ArrayLikeAbstractOperations.getRawElem(o, index) : Scriptable.NOT_FOUND;
    }

    /**
     * The put of a TypedArray for another receiver (10.4.5.5, ROADMAP P6.17, appendix C Q43): an
     * assignment reaches it when the TypedArray is in the prototype chain of the receiver ({@link
     * #base} stops there). A valid integer index is written to the receiver as a data property, an
     * invalid one is not written, and the prototypes of the TypedArray are not asked. False when
     * the receiver is the TypedArray or the key is not a CanonicalNumericIndexString: its put goes
     * on as before.
     */
    static boolean typedArrayPut(Scriptable ta, Scriptable start, int index, Object value) {
        if (start == ta) {
            return false;
        }
        if (ta.has(index, ta)) {
            start.put(index, start, value);
        }
        return true;
    }

    /** {@link #typedArrayPut(Scriptable, Scriptable, int, Object)} with a String key. */
    static boolean typedArrayPut(Scriptable ta, Scriptable start, String name, Object value) {
        if (start == ta || !canonicalNumeric(name)) {
            return false;
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(name);
        return s.stringId != null || typedArrayPut(ta, start, s.index, value);
    }

    /**
     * CanonicalNumericIndexString(name) is not undefined. Unlike upstream's {@link
     * ScriptRuntime#canonicalNumericIndexString}, "NaN" is one.
     */
    private static boolean canonicalNumeric(String name) {
        return "NaN".equals(name) || ScriptRuntime.canonicalNumericIndexString(name).isPresent();
    }

    /** proxy.[[Set]](key, value, receiver). */
    private static boolean proxySet(
            Context cx, NativeProxy proxy, Object key, Object value, Object receiver) {
        ScriptableObject target = proxy.getTargetThrowIfRevoked();
        Function trap = proxy.getTrap("set");
        if (trap == null) {
            return set(cx, target, key, value, receiver);
        }
        Object result = proxy.callTrap(trap, new Object[] {target, key, value, receiver});
        if (!ScriptRuntime.toBoolean(result)) {
            return false;
        }
        DescriptorInfo targetDesc = target.getOwnPropertyDescriptor(cx, key);
        if (targetDesc != null && targetDesc.isConfigurable(false)) {
            if (targetDesc.isDataDescriptor()
                    && targetDesc.isWritable(false)
                    && !ScriptRuntime.same(value, targetDesc.value)) {
                throw ScriptRuntime.typeError(
                        "proxy set has to use the same value as the plain call");
            }
            if (targetDesc.isAccessorDescriptor() && !(targetDesc.setter instanceof Function)) {
                throw ScriptRuntime.typeError("proxy set has to be available");
            }
        }
        return true;
    }

    /**
     * r.[[DefineOwnProperty]](key, { [[Value]]: value }), or CreateDataProperty when {@code
     * create}: through the defineProperty traps, then with put on the object at the end.
     */
    private static boolean define(
            Context cx, Scriptable r, Object key, Object value, boolean create) {
        while (r instanceof NativeProxy) {
            NativeProxy proxy = (NativeProxy) r;
            ScriptableObject target = proxy.getTargetThrowIfRevoked();
            if (proxy.getTrap("defineProperty") != null) {
                Object nf = Scriptable.NOT_FOUND;
                DescriptorInfo desc =
                        create
                                ? new DescriptorInfo(true, true, true, value)
                                : new DescriptorInfo(nf, nf, nf, nf, nf, value);
                return proxy.defineOwnProperty(cx, key, desc);
            }
            r = target;
        }
        if (r instanceof ScriptableObject) {
            ScriptableObject obj = (ScriptableObject) r;
            DescriptorInfo current = obj.getOwnPropertyDescriptor(cx, key);
            if (current == null
                    ? !obj.isExtensible()
                    : current.isConfigurable(false)
                            && (create
                                    || current.isAccessorDescriptor()
                                    || current.isWritable(false))) {
                return false;
            }
        }
        write(r, key, r, value);
        return true;
    }

    private static boolean isObject(Object value) {
        return value instanceof Scriptable && !ScriptRuntime.isSymbol(value);
    }

    private static boolean has(Scriptable obj, Object key) {
        if (key instanceof Symbol) {
            return ScriptableObject.ensureSymbolScriptable(obj).has((Symbol) key, obj);
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
        return s.stringId == null ? obj.has(s.index, obj) : obj.has(s.stringId, obj);
    }

    private static void write(Scriptable obj, Object key, Scriptable start, Object value) {
        if (key instanceof Symbol) {
            ScriptableObject.ensureSymbolScriptable(obj).put((Symbol) key, start, value);
            return;
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
        if (s.stringId == null) {
            obj.put(s.index, start, value);
        } else {
            obj.put(s.stringId, start, value);
        }
    }
}
