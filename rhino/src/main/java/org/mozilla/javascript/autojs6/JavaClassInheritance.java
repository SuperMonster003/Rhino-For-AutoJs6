/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JSFunction;
import org.mozilla.javascript.JavaInheritanceSupport;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.VarScope;

/**
 * AutoJs6 class syntax, fork only (ROADMAP D3 / D6 / T6): classes that extend a Java class or
 * interface, {@code class X extends java.lang.Thread {}}, or several interfaces at once, {@code
 * class X extends JavaAdapter(Base, I1, I2) {}} (appendix C Q3 = A). The hooks in the upstream
 * files and in {@code ClassRuntime} call the public methods here; the rest of the layer is in this
 * package (see docs/autojs6/class-syntax/NOTES.md, P5).
 *
 * <p>The class extends a {@link JavaSuperclass} in place of the Java class. Constructing the class
 * makes a {@link JavaSubclassObject} (appendix C Q4 = A) and an object of the adapter class that
 * {@code JavaAdapter.createAdapterCode} generates for the superclass, the interfaces and the
 * methods new.target overrides; the adapter calls the methods of the instance, and the instance
 * wraps the adapter.
 *
 * <p>The methods are public for the hooks; they are not an API.
 */
public final class JavaClassInheritance {

    private JavaClassInheritance() {}

    private static final Object REGISTRY_KEY = "AutoJs6JavaSuperclasses";

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /**
     * ClassRuntime.defineClass: a Java class or interface as the heritage of a class becomes its
     * {@link JavaSuperclass}; any other heritage is returned as it is.
     */
    public static Object heritage(Object heritage, Context cx, VarScope scope) {
        if (!(heritage instanceof NativeJavaClass)) {
            return heritage;
        }
        Class<?> c = ((NativeJavaClass) heritage).getClassObject();
        if (c.isInterface()) {
            checkPublic(c);
            return superclass(scope, Object.class, new Class<?>[] {c});
        }
        checkExtensible(c);
        return superclass(scope, c, new Class<?>[0]);
    }

    /**
     * {@code JavaAdapter(Base, I1, I2)}: called with Java classes and interfaces only, JavaAdapter
     * returns their {@link JavaSuperclass} for a class to extend. Null for any other call, which
     * creates an adapter object as before ({@code new JavaAdapter(Base, {...}, args)}): one whose
     * last argument is not a Java class, or with a script object among the arguments (the object
     * with the methods).
     */
    public static Scriptable superclassOf(Context cx, VarScope scope, Object[] args) {
        if (args.length == 0 || !(args[args.length - 1] instanceof NativeJavaClass)) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Scriptable && !(arg instanceof NativeJavaClass)) {
                return null;
            }
        }
        Class<?> superClass = null;
        List<Class<?>> interfaces = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (!(arg instanceof NativeJavaClass)) {
                throw ScriptRuntime.typeErrorById(
                        "msg.not.java.class.arg", String.valueOf(i), ScriptRuntime.toString(arg));
            }
            Class<?> c = ((NativeJavaClass) arg).getClassObject();
            if (!seen.add(c)) {
                throw ScriptRuntime.typeErrorById("msg.java.adapter.dup", c.getName());
            }
            if (c.isInterface()) {
                checkPublic(c);
                interfaces.add(c);
            } else if (superClass != null) {
                throw ScriptRuntime.typeErrorById(
                        "msg.only.one.super", superClass.getName(), c.getName());
            } else if (i != 0) {
                throw ScriptRuntime.typeErrorById("msg.java.adapter.class.first", c.getName());
            } else {
                checkExtensible(c);
                superClass = c;
            }
        }
        return superclass(
                scope,
                superClass == null ? Object.class : superClass,
                interfaces.toArray(new Class<?>[0]));
    }

    /**
     * JavaAdapter.createAdapterWrapper: the adapter constructor of an instance makes the instance
     * its {@code self}. Also called by the methods of the adapter class, which the Java constructor
     * may call before that. Null for the adapters of {@code new JavaAdapter(...)}, an instance
     * given to it as the object with the methods included.
     */
    public static Scriptable attach(Scriptable delegee, Object adapter) {
        if (!(delegee instanceof JavaSubclassObject) || !(adapter instanceof JavaSubclass)) {
            return null;
        }
        ((JavaSubclassObject) delegee).attach(adapter);
        return delegee;
    }

    /**
     * JavaAdapter.getFunction: the function an adapter method of an instance calls. A method found
     * as a data property is returned at once; otherwise the lookup is left to the call, which runs
     * in a Context (an accessor, the Java implementation, or the TypeError of an abstract method
     * that the class does not implement). Null for the adapters of {@code new JavaAdapter(...)}.
     */
    public static Function method(Scriptable delegee, String name) {
        if (!(delegee instanceof JavaSubclassObject)) {
            return null;
        }
        for (Scriptable p = delegee; p != null && !(p instanceof JavaPrototype); ) {
            Object value = JavaInheritanceSupport.ownDataValue(p, name);
            if (value instanceof Function) {
                return (Function) value;
            }
            if (value != Scriptable.NOT_FOUND) {
                break; // an accessor, or not a function
            }
            p = p.getPrototype();
        }
        JavaSubclassObject self = (JavaSubclassObject) delegee;
        return new LambdaFunction(
                ScriptableObject.getTopLevelScope(self.getParentScope()),
                name,
                0,
                (cx, scope, thisObj, args) -> callLate(self, name, cx, scope, thisObj, args));
    }

    private static Object callLate(
            JavaSubclassObject self,
            String name,
            Context cx,
            VarScope scope,
            Object thisObj,
            Object[] args) {
        Object value = ScriptableObject.getProperty(self, name);
        if (value instanceof Function) {
            return ((Function) value).call(cx, scope, thisObj, args);
        }
        if (value == Scriptable.NOT_FOUND || Undefined.isUndefined(value)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.java.class.abstract", self.adapter().declaringClass(name), name);
        }
        throw ScriptRuntime.typeErrorById(
                "msg.java.class.override.not.function",
                name,
                self.adapter().superclass().javaName());
    }

    /**
     * WrapFactory.wrap / wrapNewObject: wrapping the Java object of an instance gives the instance
     * again, so that it keeps its class when Java code hands it back. Null for other objects.
     */
    public static Scriptable selfOf(Object javaObject) {
        if (!(javaObject instanceof JavaSubclass)) {
            return null;
        }
        Object self;
        try {
            self = javaObject.getClass().getField("delegee").get(javaObject);
        } catch (ReflectiveOperationException e) {
            return null;
        }
        if (self instanceof JavaSubclassObject
                && ((JavaSubclassObject) self).unwrap() == javaObject) {
            return (Scriptable) self;
        }
        return null;
    }

    /**
     * ClassRuntime, this before {@code super()}: the name of the Java superclass when the derived
     * constructor extends one, for the message that says the Java object does not exist yet.
     */
    public static String superclassName(JSFunction constructor) {
        Scriptable parent = constructor.getPrototype();
        return parent instanceof JavaSuperclass ? ((JavaSuperclass) parent).javaName() : null;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * [[Construct]] of a {@link JavaSuperclass}, from {@code super(args)} with the class as
     * new.target: generates (or finds) the adapter class for the overridden methods, creates the
     * instance and the Java object with the Java constructor the arguments select.
     */
    static Scriptable construct(
            JavaSuperclass base, Context cx, Object newTarget, VarScope scope, Object[] args) {
        Scriptable proto = base.javaPrototype;
        if (newTarget instanceof Scriptable) {
            Object p = ScriptableObject.getProperty((Scriptable) newTarget, "prototype");
            if (p instanceof Scriptable) {
                proto = (Scriptable) p;
            }
        }
        Map<String, Integer> names = overriddenMethods(base, proto, scope);
        Class<?> type =
                JavaInheritanceSupport.adapterClass(
                        scope, base.superClass, base.adapterInterfaces, names);
        JavaSubclassObject self =
                new JavaSubclassObject(
                        base.adapter(type), ScriptableObject.getTopLevelScope(scope), proto);
        base.newInstance(cx, self.adapter(), self, args);
        if (self.unwrap() == null) {
            throw new IllegalStateException("adapter " + type.getName() + " has no self");
        }
        self.constructed();
        return self;
    }

    /**
     * The methods of the class (and of its JavaScript superclasses) that override Java methods: the
     * functions on the prototype chain from new.target's prototype up to the Java superclass. The
     * arity does not matter, so it is 0 and the adapter class only depends on the names.
     */
    private static Map<String, Integer> overriddenMethods(
            JavaSuperclass base, Scriptable proto, VarScope scope) {
        Map<String, Integer> names = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Scriptable objectProto = ScriptableObject.getObjectPrototype(scope);
        for (Scriptable p = proto;
                p != null && p != objectProto && !(p instanceof JavaPrototype);
                p = p.getPrototype()) {
            for (String name : JavaInheritanceSupport.ownFunctionNames(p)) {
                if (name.equals("constructor") || !seen.add(name)) {
                    continue;
                }
                if (base.overrides(name)) {
                    names.put(name, 0);
                } else if (base.isFinal(name)) {
                    throw ScriptRuntime.typeErrorById(
                            "msg.java.class.final.method", name, base.javaName());
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Superclasses
    // ------------------------------------------------------------------

    private static void checkExtensible(Class<?> c) {
        if (c.isPrimitive() || c.isArray() || Modifier.isFinal(c.getModifiers())) {
            throw ScriptRuntime.typeErrorById("msg.java.class.final", c.getName());
        }
        checkPublic(c);
    }

    private static void checkPublic(Class<?> c) {
        if (!Modifier.isPublic(c.getModifiers())) {
            throw ScriptRuntime.typeErrorById("msg.java.class.not.public", c.getName());
        }
    }

    /** The superclass of a scope for a Java class and interfaces, made once. */
    @SuppressWarnings("unchecked")
    private static JavaSuperclass superclass(
            VarScope scope, Class<?> superClass, Class<?>[] interfaces) {
        TopLevel top = ScriptableObject.getTopLevelScope(scope);
        Map<List<Class<?>>, JavaSuperclass> registry =
                (Map<List<Class<?>>, JavaSuperclass>) top.getAssociatedValue(REGISTRY_KEY);
        if (registry == null) {
            registry =
                    (Map<List<Class<?>>, JavaSuperclass>)
                            top.associateValue(REGISTRY_KEY, new ConcurrentHashMap<>());
        }
        List<Class<?>> key = new ArrayList<>(interfaces.length + 1);
        key.add(superClass);
        key.addAll(Arrays.asList(interfaces));
        return registry.computeIfAbsent(key, k -> new JavaSuperclass(top, superClass, interfaces));
    }
}
