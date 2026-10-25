/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

import java.io.Serial;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.VarScope;

/**
 * AutoJs6 class syntax, fork only (ROADMAP D3 / T6): the {@code prototype} of a {@link
 * JavaSuperclass}, which plays the role of the prototype of the Java class. Looked up from a class
 * instance ({@link JavaSubclassObject}), it has the members of the Java object: fields and bean
 * properties, the methods the class does not override, and for an overridden method the Java
 * implementation ({@code super$} method). So {@code super.m()} calls the Java method, and the
 * JavaScript methods of the class come first in the prototype chain.
 *
 * <p>Its own properties come before the Java members; its prototype is {@code Object.prototype}.
 */
final class JavaPrototype extends ScriptableObject {
    @Serial private static final long serialVersionUID = 1L;

    private final JavaSuperclass constructor;

    private transient volatile ConcurrentHashMap<String, LambdaFunction> superMethods;

    JavaPrototype(JavaSuperclass constructor, VarScope scope) {
        this.constructor = constructor;
        setParentScope(scope);
        setPrototype(ScriptableObject.getObjectPrototype(scope));
        defineProperty("constructor", constructor, DONTENUM);
    }

    @Override
    public String getClassName() {
        return "Object";
    }

    @Override
    public boolean has(String name, Scriptable start) {
        return super.has(name, start)
                || (start instanceof JavaSubclassObject
                        && ((JavaSubclassObject) start).hasJavaMember(name));
    }

    @Override
    public Object get(String name, Scriptable start) {
        Object value = super.get(name, start);
        if (value != NOT_FOUND) {
            return value;
        }
        if (start instanceof JavaSubclassObject) {
            return ((JavaSubclassObject) start).getJavaMember(name);
        }
        if (start == this && constructor.hasInstanceMethod(name)) {
            return superMethod(name);
        }
        return NOT_FOUND;
    }

    /**
     * {@code super.m(args)} looks {@code m} up with this object as the start, not the instance (as
     * Rhino does for any super call), and calls it with the instance as {@code this}. The method of
     * the Java object is found when it is called, from the {@code this} it gets.
     */
    private LambdaFunction superMethod(String name) {
        ConcurrentHashMap<String, LambdaFunction> methods = superMethods;
        if (methods == null) {
            methods = new ConcurrentHashMap<>();
            superMethods = methods;
        }
        return methods.computeIfAbsent(
                name,
                n ->
                        new LambdaFunction(
                                ScriptableObject.getTopLevelScope(getParentScope()),
                                n,
                                0,
                                (cx, scope, thisObj, args) ->
                                        callSuper(n, cx, scope, thisObj, args)));
    }

    private Object callSuper(
            String name, Context cx, VarScope scope, Object thisObj, Object[] args) {
        Object method =
                thisObj instanceof JavaSubclassObject
                        ? ScriptableObject.getSuperProperty(this, (Scriptable) thisObj, name)
                        : ScriptableObject.getProperty(getPrototype(), name);
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(method, name);
        }
        return ((Callable) method).call(cx, scope, thisObj, args);
    }

    /**
     * Assigns a writable Java field or bean property of the instance; anything else becomes an own
     * property of the instance, as an assignment that reaches a prototype does.
     */
    @Override
    public void put(String name, Scriptable start, Object value) {
        if (start instanceof JavaSubclassObject
                && !super.has(name, this)
                && ((JavaSubclassObject) start).putJavaMember(name, value)) {
            return;
        }
        super.put(name, start, value);
    }

    @Override
    public boolean has(Symbol key, Scriptable start) {
        return super.has(key, start)
                || (start instanceof JavaSubclassObject
                        && ((JavaSubclassObject) start).hasJavaMember(key));
    }

    @Override
    public Object get(Symbol key, Scriptable start) {
        Object value = super.get(key, start);
        if (value == NOT_FOUND && start instanceof JavaSubclassObject) {
            value = ((JavaSubclassObject) start).getJavaMember(key);
        }
        return value;
    }
}
