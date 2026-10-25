/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.autojs6;

import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.JavaInheritanceSupport;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.lc.member.ExecutableBox;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.lc.type.TypeInfoFactory;

/**
 * AutoJs6 class syntax, fork only (ROADMAP D3 / T6): the constructor a class extends in place of a
 * Java class or interface, or of {@code JavaAdapter(Base, I1, I2)} (appendix C Q3). Its {@code
 * prototype} is the {@link JavaPrototype} at the end of the JavaScript part of the prototype chain;
 * its own [[Construct]] builds the Java object with the methods of new.target overridden, and it
 * shows the static members of the Java class.
 */
public final class JavaSuperclass extends BaseFunction {
    @Serial private static final long serialVersionUID = 1L;

    final transient Class<?> superClass;
    final transient Class<?>[] interfaces;

    /** The interfaces of the adapter classes: the given ones and {@link JavaSubclass}. */
    final transient Class<?>[] adapterInterfaces;

    final JavaPrototype javaPrototype;
    private final NativeJavaClass statics;
    private final String name;

    /** Methods that an adapter class overrides, by name. */
    private final transient Set<String> overridable = new HashSet<>();

    /** Instance methods that are final, without an overload that is not. */
    private final transient Set<String> finalMethods = new HashSet<>();

    /** The public and protected instance methods of the superclass and the interfaces. */
    private final transient Set<String> instanceMethods = new HashSet<>();

    /** The public and protected constructors of the superclass, for the overload resolution. */
    private final transient ExecutableBox[] ctors;

    private final transient ConcurrentHashMap<Class<?>, Adapter> adapters =
            new ConcurrentHashMap<>();

    JavaSuperclass(VarScope scope, Class<?> superClass, Class<?>[] interfaces) {
        super(scope, ScriptableObject.getFunctionPrototype(scope));
        this.superClass = superClass;
        this.interfaces = interfaces;
        this.adapterInterfaces = new Class<?>[interfaces.length + 1];
        System.arraycopy(interfaces, 0, adapterInterfaces, 0, interfaces.length);
        adapterInterfaces[interfaces.length] = JavaSubclass.class;
        Class<?> staticsClass =
                superClass == Object.class && interfaces.length > 0 ? interfaces[0] : superClass;
        this.statics = new NativeJavaClass(scope, staticsClass, true);
        this.name = displayName(superClass, interfaces);

        for (Method m : JavaInheritanceSupport.overridableMethods(superClass)) {
            overridable.add(m.getName());
        }
        for (Class<?> i : interfaces) {
            for (Method m : i.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && !m.isDefault()) {
                    overridable.add(m.getName());
                }
            }
        }
        for (Class<?> c = superClass; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isStatic(mods)
                        || !(Modifier.isPublic(mods) || Modifier.isProtected(mods))) {
                    continue;
                }
                instanceMethods.add(m.getName());
                if (Modifier.isFinal(mods) && !overridable.contains(m.getName())) {
                    finalMethods.add(m.getName());
                }
            }
        }
        for (Class<?> c : interfaces) {
            for (Method m : c.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    instanceMethods.add(m.getName());
                }
            }
        }
        for (Method m : superClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                instanceMethods.add(m.getName());
            }
        }
        List<ExecutableBox> boxes = new ArrayList<>();
        TypeInfoFactory types = TypeInfoFactory.get(scope);
        for (Constructor<?> c : superClass.getDeclaredConstructors()) {
            int mods = c.getModifiers();
            if (Modifier.isPublic(mods) || Modifier.isProtected(mods)) {
                boxes.add(new ExecutableBox(c, types));
            }
        }
        this.ctors = boxes.toArray(new ExecutableBox[0]);

        this.javaPrototype = new JavaPrototype(this, scope);
        setPrototypeProperty(javaPrototype);
        setPrototypePropertyAttributes(READONLY | DONTENUM | PERMANENT);
    }

    private static String displayName(Class<?> superClass, Class<?>[] interfaces) {
        if (interfaces.length == 0) {
            return superClass.getName();
        }
        if (superClass == Object.class && interfaces.length == 1) {
            return interfaces[0].getName();
        }
        StringBuilder sb = new StringBuilder("JavaAdapter(");
        if (superClass != Object.class) {
            sb.append(superClass.getName()).append(", ");
        }
        for (int i = 0; i < interfaces.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(interfaces[i].getName());
        }
        return sb.append(')').toString();
    }

    /** The name of the Java class (or of the interface, or of the JavaAdapter call). */
    String javaName() {
        return name;
    }

    boolean overrides(String method) {
        return overridable.contains(method);
    }

    boolean isFinal(String method) {
        return finalMethods.contains(method);
    }

    boolean hasInstanceMethod(String method) {
        return instanceMethods.contains(method);
    }

    @Override
    public String getFunctionName() {
        return name;
    }

    @Override
    public int getLength() {
        return 0;
    }

    @Override
    public int getArity() {
        return 0;
    }

    @Override
    public Object call(Context cx, Object nt, VarScope s, Object thisObj, Object[] args) {
        throw ScriptRuntime.typeErrorById("msg.class.ctor.call", name);
    }

    @Override
    public Scriptable construct(Context cx, Object nt, VarScope scope, Object[] args) {
        return JavaClassInheritance.construct(this, cx, nt, scope, args);
    }

    // The static members of the Java class, after the own properties.

    @Override
    public boolean has(String name, Scriptable start) {
        return super.has(name, start) || statics.has(name, statics);
    }

    @Override
    public Object get(String name, Scriptable start) {
        Object value = super.get(name, start);
        if (value == NOT_FOUND && statics.has(name, statics)) {
            value = statics.get(name, statics);
        }
        return value;
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (!super.has(name, this) && statics.has(name, statics)) {
            statics.put(name, statics, value);
            return;
        }
        super.put(name, start, value);
    }

    private static final String INACCESSIBLE = "java.lang.reflect.InaccessibleObjectException";

    Adapter adapter(Class<?> type) {
        return adapters.computeIfAbsent(type, Adapter::new);
    }

    /**
     * Creates the adapter object with the superclass constructor that the overload resolution picks
     * for the arguments; the generated constructor hands the Java object to {@code self}.
     */
    void newInstance(Context cx, Adapter adapter, JavaSubclassObject self, Object[] args) {
        int index =
                ctors.length == 0 ? -1 : JavaInheritanceSupport.findConstructor(cx, ctors, args);
        if (index < 0) {
            throw ScriptRuntime.typeErrorById(
                    "msg.java.class.no.ctor", name, JavaInheritanceSupport.scriptSignature(args));
        }
        ExecutableBox ctor = ctors[index];
        Object[] javaArgs = ctor.wrapArgsInternal(args, Collections.emptyMap());
        Object[] adapterArgs = new Object[javaArgs.length + 2];
        adapterArgs[0] = self;
        adapterArgs[1] = cx.getFactory();
        System.arraycopy(javaArgs, 0, adapterArgs, 2, javaArgs.length);
        try {
            adapter.constructor(index, ctor).newInstance(adapterArgs);
        } catch (InvocationTargetException e) {
            throw Context.throwAsScriptRuntimeEx(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw Context.throwAsScriptRuntimeEx(e);
        }
    }

    /** What an instance needs to know about its adapter class. */
    final class Adapter {
        final Class<?> type;

        /** Methods the adapter class implements by calling the functions of the instance. */
        private final Set<String> dispatching = new HashSet<>();

        /** Overridden methods that have a {@code super$} method calling the Java implementation. */
        private final Set<String> supers = new HashSet<>();

        private final Constructor<?>[] constructors = new Constructor<?>[ctors.length];

        /**
         * Whether the views of the Java objects have the protected members; false once reflecting
         * them failed, which happens for the protected members of a JDK class on a desktop JVM (its
         * module does not open them). Android has no modules.
         */
        private volatile boolean protectedMembers = true;

        Adapter(Class<?> type) {
            this.type = type;
            for (Method m : type.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("super$")) {
                    supers.add(n.substring("super$".length()));
                } else {
                    dispatching.add(n);
                }
            }
        }

        JavaSuperclass superclass() {
            return JavaSuperclass.this;
        }

        /** The Java object as a Java object wrapper with the protected members when possible. */
        NativeJavaObject view(VarScope scope, Object javaObject) {
            if (protectedMembers) {
                try {
                    return new NativeJavaObject(scope, javaObject, TypeInfo.NONE, true);
                } catch (RuntimeException e) {
                    if (!e.getClass().getName().equals(INACCESSIBLE)) {
                        throw e;
                    }
                    protectedMembers = false;
                }
            }
            return new NativeJavaObject(scope, javaObject, TypeInfo.NONE, false);
        }

        /** The internal members of the adapter class, which the instance does not show. */
        boolean hides(String member) {
            return member.startsWith("super$")
                    || member.equals("factory")
                    || member.equals("delegee")
                    || member.equals("self");
        }

        boolean dispatches(String method) {
            return dispatching.contains(method);
        }

        boolean hasSuper(String method) {
            return supers.contains(method);
        }

        Constructor<?> constructor(int index, ExecutableBox superCtor)
                throws NoSuchMethodException {
            Constructor<?> c = constructors[index];
            if (c == null) {
                Class<?>[] params = superCtor.asConstructor().getParameterTypes();
                Class<?>[] types = new Class<?>[params.length + 2];
                types[0] = Scriptable.class;
                types[1] = ContextFactory.class;
                System.arraycopy(params, 0, types, 2, params.length);
                c = type.getConstructor(types);
                constructors[index] = c;
            }
            return c;
        }

        /** The Java class that declares an abstract method, for the error of calling it. */
        String declaringClass(String method) {
            List<Class<?>> types = new ArrayList<>();
            for (Class<?> c = superClass; c != null; c = c.getSuperclass()) {
                types.add(c);
                Collections.addAll(types, c.getInterfaces());
            }
            Collections.addAll(types, interfaces);
            for (Class<?> c : types) {
                for (Method m : c.isInterface() ? c.getMethods() : c.getDeclaredMethods()) {
                    if (m.getName().equals(method) && Modifier.isAbstract(m.getModifiers())) {
                        return m.getDeclaringClass().getName();
                    }
                }
            }
            return name;
        }
    }
}
