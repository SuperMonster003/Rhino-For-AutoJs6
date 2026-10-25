/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

import java.io.Serial;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.JavaInheritanceSupport;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolKey;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.Wrapper;

/**
 * AutoJs6 class syntax, fork only (ROADMAP D3, appendix C Q4 = A): an instance of a class that
 * extends a Java class. It wraps the adapter object (passing it to Java passes that object), is the
 * {@code delegee} and the {@code self} of the adapter, and is an ordinary script object otherwise:
 * its prototype is new.target's {@code prototype}, and the fields (P3) and private elements are its
 * own. The Java members are reached through the {@link JavaPrototype} in the prototype chain.
 */
public final class JavaSubclassObject extends ScriptableObject implements Wrapper {
    @Serial private static final long serialVersionUID = 1L;

    private final transient JavaSuperclass.Adapter adapter;

    /** The adapter object; null until the adapter constructor has called the Java constructor. */
    private transient volatile Object javaObject;

    private transient volatile NativeJavaObject view;

    JavaSubclassObject(JavaSuperclass.Adapter adapter, VarScope scope, Scriptable prototype) {
        this.adapter = adapter;
        setParentScope(scope);
        setPrototype(prototype);
    }

    /** The first adapter object stays: it is the one whose {@code delegee} is this instance. */
    void attach(Object javaObject) {
        if (this.javaObject == null) {
            this.javaObject = javaObject;
        }
    }

    JavaSuperclass.Adapter adapter() {
        return adapter;
    }

    @Override
    public String getClassName() {
        return "JavaObject";
    }

    @Override
    public Object unwrap() {
        return javaObject;
    }

    /** The Java object as a Java object wrapper with the protected members, for the lookups. */
    private NativeJavaObject view() {
        NativeJavaObject v = view;
        if (v == null) {
            v = adapter.view(getParentScope(), javaObject);
            view = v;
        }
        return v;
    }

    boolean hasJavaMember(String name) {
        if (javaObject == null || adapter.hides(name)) {
            return false;
        }
        if (adapter.dispatches(name)) {
            return adapter.hasSuper(name);
        }
        NativeJavaObject v = view();
        return v.has(name, v);
    }

    /**
     * A member of the Java object: for a method the adapter overrides, the Java implementation
     * ({@code super$} method) or nothing when it is abstract, so that no lookup gets the adapter
     * method that calls back into the instance.
     */
    Object getJavaMember(String name) {
        if (javaObject == null || adapter.hides(name)) {
            return NOT_FOUND;
        }
        NativeJavaObject v = view();
        if (adapter.dispatches(name)) {
            return adapter.hasSuper(name) ? v.get("super$" + name, v) : NOT_FOUND;
        }
        return v.get(name, v);
    }

    /** Assigns a writable Java field or bean property; false when the name is not one. */
    boolean putJavaMember(String name, Object value) {
        if (javaObject == null || adapter.hides(name) || adapter.dispatches(name)) {
            return false;
        }
        NativeJavaObject v = view();
        if (!JavaInheritanceSupport.isWritableJavaProperty(v, name)) {
            return false;
        }
        v.put(name, v, value);
        return true;
    }

    boolean hasJavaMember(Symbol key) {
        return javaObject instanceof Iterable && SymbolKey.ITERATOR.equals(key);
    }

    /** {@code Symbol.iterator} of a Java {@code Iterable}, as for a Java object wrapper. */
    Object getJavaMember(Symbol key) {
        if (!hasJavaMember(key)) {
            return NOT_FOUND;
        }
        NativeJavaObject v = view();
        Object iterator = v.get(key, v);
        if (!(iterator instanceof Callable)) {
            return NOT_FOUND;
        }
        Callable method = (Callable) iterator;
        return (Callable) (cx, scope, thisObj, args) -> method.call(cx, scope, v, args);
    }
}
