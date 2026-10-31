/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeJavaList;
import org.mozilla.javascript.NativeJavaMap;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.Wrapper;

/**
 * AutoJs6 class syntax, fork only (ROADMAP P7.2a, appendix C Q15 = A): what the {@link WrapFactory}
 * of a Context adds to the Java object of a class instance. A host can wrap a Java object in a
 * wrapper of its own (AutoJs6 wraps a View in a NativeView, with {@code attr}, {@code on} and
 * more). The WrapFactory hook gives the instance back in place of such a wrapper, so the names the
 * wrapper has are looked up here, after the JavaScript members of the class and the Java members.
 *
 * <p>The functions run with the wrapper as {@code this}, which is what they need; when one gives
 * the Java object back, the script gets the instance.
 */
final class HostExtensions {
    private final JavaSubclassObject self;
    private final Object javaObject;

    /** The factory of the wrapper: a Java thread has another Context, with its own factory. */
    final WrapFactory factory;

    /** Null when the factory makes a wrapper of Rhino itself, which adds no name of a host. */
    private final Scriptable wrapper;

    private final ConcurrentHashMap<String, LambdaFunction> functions = new ConcurrentHashMap<>();

    HostExtensions(JavaSubclassObject self, Object javaObject, Context cx) {
        this.self = self;
        this.javaObject = javaObject;
        factory = cx.getWrapFactory();
        Scriptable w =
                factory.wrapAsJavaObject(cx, self.getParentScope(), javaObject, Object.class);
        wrapper = w == self || isPlain(w) ? null : w;
    }

    /**
     * A NativeJavaList or NativeJavaMap would add {@code length} and the keys of the map, and ask
     * the methods the class overrides for them on every lookup that misses.
     */
    private static boolean isPlain(Scriptable w) {
        return w == null
                || w.getClass() == NativeJavaObject.class
                || w.getClass() == NativeJavaList.class
                || w.getClass() == NativeJavaMap.class;
    }

    boolean has(String name) {
        return wrapper != null && wrapper.has(name, wrapper);
    }

    Object get(String name) {
        if (wrapper == null) {
            return Scriptable.NOT_FOUND;
        }
        Object value = wrapper.get(name, wrapper);
        return value instanceof Callable ? function(name) : instanceFor(value);
    }

    private LambdaFunction function(String name) {
        return functions.computeIfAbsent(
                name,
                n ->
                        new LambdaFunction(
                                ScriptableObject.getTopLevelScope(self.getParentScope()),
                                n,
                                0,
                                (cx, scope, thisObj, args) -> call(n, cx, scope, args)));
    }

    private Object call(String name, Context cx, VarScope scope, Object[] args) {
        Object method = wrapper.get(name, wrapper);
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(method, name);
        }
        return instanceFor(((Callable) method).call(cx, scope, wrapper, args));
    }

    private Object instanceFor(Object value) {
        boolean same =
                value == javaObject
                        || (value instanceof Wrapper && ((Wrapper) value).unwrap() == javaObject);
        return same ? self : value;
    }
}
