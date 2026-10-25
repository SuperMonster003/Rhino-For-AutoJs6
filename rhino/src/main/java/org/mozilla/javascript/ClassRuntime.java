/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AutoJs6 class syntax: the runtime primitives of class definitions and derived constructors
 * (ROADMAP P2.2), shared by the interpreter and the compiler.
 *
 * <p>A derived constructor runs with a {@link DerivedThis} as its this value until {@code super()}
 * binds it. The holder never escapes to script code: the {@code this} of a derived constructor is
 * read through {@link #thisValue}, and every runtime operation that receives the this value of the
 * current function unwraps it with {@link #receiver} or {@link #unwrapThis}.
 *
 * <p>The methods are public for the compiled code to call them; they are not an API.
 */
public final class ClassRuntime {

    private ClassRuntime() {}

    /** The this value of a derived constructor, which {@code super()} initializes. */
    static final class DerivedThis {
        final JSFunction constructor;
        Scriptable value;

        DerivedThis(JSFunction constructor) {
            this.constructor = constructor;
        }

        @Override
        public String toString() {
            return value == null ? "[uninitialized this]" : value.toString();
        }
    }

    /** Creates the this value a constructor runs with: a holder for a derived constructor. */
    static Object newDerivedThis(JSFunction constructor) {
        return new DerivedThis(constructor);
    }

    /** [[Construct]] of a derived constructor: the body starts without a bound this. */
    static Scriptable constructDerived(
            Context cx, JSFunction f, Object newTarget, VarScope scope, Object[] args) {
        Object thisObj = newDerivedThis(f);
        Object result =
                f.getDescriptor().getConstructor().execute(cx, f, newTarget, scope, thisObj, args);
        return derivedResult(result, thisObj);
    }

    /**
     * Returns whether the function is a class constructor: the only functions that can be
     * constructed and have a home object.
     */
    static boolean isClassConstructor(JSFunction f) {
        return f.getHomeObject() != null && f.getDescriptor().getConstructor() != null;
    }

    /** Whether the function is a method, which cannot be constructed. */
    static boolean isMethod(JSFunction f) {
        return f.getHomeObject() != null && !isClassConstructor(f);
    }

    /**
     * Whether the interpreter may run {@code new f()} in its own frame loop: not for a derived
     * constructor, whose this and result {@link #constructDerived} handles.
     */
    static boolean inlinesNew(Object f) {
        return f instanceof JSFunction && !((JSFunction) f).getDescriptor().isDerivedConstructor();
    }

    /** IsConstructor, for a bound function. */
    static boolean isConstructor(Object target) {
        return AbstractEcmaObjectOperations.isConstructor(null, target);
    }

    /**
     * ClassDefinitionEvaluation up to the definition of the elements: validates the heritage,
     * creates the prototype and the constructor F and links them.
     */
    public static Object defineClass(
            Object heritage,
            boolean hasHeritage,
            JSDescriptor<JSFunction> constructor,
            Context cx,
            VarScope scope) {
        Scriptable protoParent;
        Scriptable constructorParent;
        if (!hasHeritage) {
            protoParent = ScriptableObject.getObjectPrototype(scope);
            constructorParent = ScriptableObject.getFunctionPrototype(scope);
        } else if (heritage == null) {
            protoParent = null;
            constructorParent = ScriptableObject.getFunctionPrototype(scope);
        } else {
            if (!AbstractEcmaObjectOperations.isConstructor(cx, heritage)) {
                throw ScriptRuntime.typeErrorById("msg.class.extends.not.ctor", describe(heritage));
            }
            Object parentProto = ScriptRuntime.getObjectProp(heritage, "prototype", cx, scope);
            if (parentProto != null && !(parentProto instanceof Scriptable)) {
                throw ScriptRuntime.typeErrorById("msg.class.extends.proto", describe(parentProto));
            }
            protoParent = (Scriptable) parentProto;
            constructorParent = (Scriptable) heritage;
        }

        NativeObject proto = new NativeObject();
        proto.setParentScope(scope);
        proto.setPrototype(protoParent);

        JSFunction f = new JSFunction(scope, constructor, null, Undefined.instance, proto);
        if (cx.getLanguageVersion() >= Context.VERSION_ES6) {
            f.setStandardPropertyAttributes(ScriptableObject.READONLY | ScriptableObject.DONTENUM);
        }
        removeLegacyProperties(f);
        f.setPrototype(constructorParent);
        f.setPrototypePropertyAttributes(
                ScriptableObject.READONLY | ScriptableObject.DONTENUM | ScriptableObject.PERMANENT);
        f.setPrototypeProperty(proto);
        proto.defineProperty("constructor", f, ScriptableObject.DONTENUM);
        return f;
    }

    /**
     * ClassElementEvaluation of a method, accessor or generator method: converts the key, creates
     * the method with its home object and defines it, non-enumerable, on the prototype (or on F
     * when it is static). Returns F.
     */
    public static Object defineElement(
            Object classConstructor,
            Object key,
            JSDescriptor<JSFunction> method,
            int flags,
            Context cx,
            VarScope scope) {
        JSFunction f = (JSFunction) classConstructor;
        ScriptableObject home =
                (flags & ClassOp.ELEMENT_STATIC) != 0
                        ? f
                        : (ScriptableObject) f.getPrototypeProperty();
        Object id = toPropertyKey(key);
        JSFunction fn = new JSFunction(cx, scope, method, null, Undefined.instance, home);
        removeLegacyProperties(fn);
        int kind = flags & ClassOp.ELEMENT_KIND_MASK;
        if ((flags & ClassOp.ELEMENT_COMPUTED) != 0) {
            String prefix =
                    kind == ClassOp.ELEMENT_GETTER
                            ? "get "
                            : kind == ClassOp.ELEMENT_SETTER ? "set " : "";
            fn.setFunctionName(prefix + functionName(id));
        }
        if (method.isES6Generator()) {
            // A generator method gets a fresh prototype for its generator objects, without a
            // constructor property (MethodDefinitionEvaluation of a GeneratorMethod).
            NativeObject generatorProto = new NativeObject();
            generatorProto.setParentScope(scope);
            VarScope top = ScriptableObject.getTopLevelScope(scope);
            Object parent = ScriptableObject.getTopScopeValue(top, ES6Generator.GENERATOR_TAG);
            if (parent instanceof Scriptable) {
                generatorProto.setPrototype((Scriptable) parent);
            }
            fn.setPrototypePropertyAttributes(
                    ScriptableObject.DONTENUM | ScriptableObject.PERMANENT);
            fn.setPrototypeProperty(generatorProto);
        }
        ScriptableObject.DescriptorInfo desc;
        if (kind == ClassOp.ELEMENT_GETTER) {
            desc =
                    new ScriptableObject.DescriptorInfo(
                            Boolean.FALSE,
                            Scriptable.NOT_FOUND,
                            Boolean.TRUE,
                            fn,
                            Scriptable.NOT_FOUND,
                            Scriptable.NOT_FOUND);
        } else if (kind == ClassOp.ELEMENT_SETTER) {
            desc =
                    new ScriptableObject.DescriptorInfo(
                            Boolean.FALSE,
                            Scriptable.NOT_FOUND,
                            Boolean.TRUE,
                            Scriptable.NOT_FOUND,
                            fn,
                            Scriptable.NOT_FOUND);
        } else {
            desc = new ScriptableObject.DescriptorInfo(false, true, true, fn);
        }
        if (home == f) {
            makeOrdinary(f, id);
        }
        home.defineOwnProperty(cx, id, desc);
        return f;
    }

    /**
     * Class constructors and methods are strict functions: they have no own {@code arity} (a Rhino
     * extension) or {@code arguments} property.
     */
    private static void removeLegacyProperties(BaseFunction f) {
        f.getMap().compute(f, "arity", 0, (k, i, existing, map, owner) -> null);
        f.getMap().compute(f, "arguments", 0, (k, i, existing, map, owner) -> null);
    }

    /**
     * Turns a configurable built-in property of the constructor ({@code length}, {@code name}) into
     * an ordinary data property in place, for a static method of that name to replace it without
     * changing the property order.
     */
    private static void makeOrdinary(JSFunction f, Object id) {
        if (!(id instanceof String)) {
            return;
        }
        f.getMap()
                .compute(
                        f,
                        id,
                        0,
                        (k, i, existing, map, owner) -> {
                            if (existing instanceof BuiltInSlot
                                    && (existing.getAttributes() & ScriptableObject.PERMANENT)
                                            == 0) {
                                StandardSlot<Scriptable> slot = new StandardSlot<>(existing);
                                slot.value = existing.getValue(f);
                                return slot;
                            }
                            return existing;
                        });
    }

    /**
     * The {@code arguments} and {@code caller} accessors of Function.prototype throw for the
     * functions of class code, which is strict (AddRestrictedFunctionProperties). Other functions,
     * strict ones included, keep Rhino's behavior: {@code arguments} of a function that is not
     * running is null, and functions have no {@code caller}.
     */
    static void checkRestricted(Scriptable thisObj, String name) {
        if (thisObj instanceof JSFunction && ((JSFunction) thisObj).getDescriptor().classCode) {
            throw ScriptRuntime.typeErrorById("msg.class.restricted.prop", name);
        }
    }

    static Object restrictedCallerGetter(Scriptable thisObj) {
        checkRestricted(thisObj, "caller");
        return Undefined.instance;
    }

    static void restrictedCallerSetter(Scriptable thisObj, Object value) {
        checkRestricted(thisObj, "caller");
    }

    /** ToPropertyKey. */
    static Object toPropertyKey(Object key) {
        if (key instanceof String) {
            return key;
        }
        if (key instanceof Symbol) {
            return key;
        }
        Object primitive = ScriptRuntime.toPrimitive(key, ScriptRuntime.StringClass);
        if (primitive instanceof Symbol) {
            return primitive;
        }
        return ScriptRuntime.toString(primitive);
    }

    /** The name SetFunctionName gives a function for a property key. */
    private static String functionName(Object id) {
        if (id instanceof Symbol) {
            Object description;
            if (id instanceof NativeSymbol) {
                description = ((NativeSymbol) id).getKey().getDescription();
            } else if (id instanceof SymbolKey) {
                description = ((SymbolKey) id).getDescription();
            } else {
                description = ((Symbol) id).getName();
            }
            return Undefined.isUndefined(description) ? "" : "[" + description + "]";
        }
        return ScriptRuntime.toString(id);
    }

    /** The [[Call]] of a class constructor throws. */
    public static Object checkConstructorCall(Object newTarget, Object function) {
        if (newTarget == null || Undefined.isUndefined(newTarget)) {
            String name =
                    function instanceof BaseFunction
                            ? ((BaseFunction) function).getFunctionName()
                            : "";
            throw ScriptRuntime.typeErrorById("msg.class.ctor.call", name);
        }
        return Undefined.instance;
    }

    /** Reads {@code this} in a derived constructor or in an arrow function inside it. */
    public static Object thisValue(Object thisObj) {
        if (thisObj instanceof DerivedThis) {
            Scriptable value = ((DerivedThis) thisObj).value;
            if (value == null) {
                throw ScriptRuntime.referenceError(
                        ScriptRuntime.getMessageById("msg.class.this.uninit"));
            }
            return value;
        }
        return thisObj;
    }

    /**
     * Returns the this value an operation on the current function's this (a super property access)
     * works on: like reading {@code this}, it fails before {@code super()}.
     */
    public static Object receiver(Object thisObj) {
        return thisValue(thisObj);
    }

    /**
     * Returns the this value passed on where the current this may be uninitialized without that
     * being an error yet (the this of direct eval code, the debugger): undefined before {@code
     * super()}.
     */
    public static Object unwrapThis(Object thisObj) {
        if (thisObj instanceof DerivedThis) {
            Scriptable value = ((DerivedThis) thisObj).value;
            return value == null ? Undefined.instance : value;
        }
        return thisObj;
    }

    /** GetSuperConstructor: the [[Prototype]] of the derived constructor. */
    public static Object superConstructor(Object thisObj) {
        if (!(thisObj instanceof DerivedThis)) {
            throw Kit.codeBug();
        }
        return ((DerivedThis) thisObj).constructor.getPrototype();
    }

    /**
     * SuperCall: constructs the parent with the current new.target and binds the this of the
     * derived constructor. Returns the bound this.
     */
    public static Object superCall(
            Object superConstructor,
            Object[] args,
            Context cx,
            VarScope scope,
            Object thisObj,
            Object newTarget) {
        if (!(thisObj instanceof DerivedThis)) {
            throw Kit.codeBug();
        }
        if (!AbstractEcmaObjectOperations.isConstructor(cx, superConstructor)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.class.super.not.ctor", describe(superConstructor));
        }
        Scriptable result =
                ((Constructable) superConstructor).construct(cx, newTarget, scope, args);
        if (!(superConstructor instanceof JSFunction)
                && newTarget instanceof Scriptable
                && newTarget != superConstructor) {
            // Built-in constructors other than Object and Function ignore new.target until
            // ROADMAP P4.3 hands it to them: until then the instance gets the prototype that
            // OrdinaryCreateFromConstructor would have given it.
            Object proto = ScriptableObject.getProperty((Scriptable) newTarget, "prototype");
            if (proto instanceof Scriptable && result.getPrototype() != proto) {
                result.setPrototype((Scriptable) proto);
            }
        }
        DerivedThis holder = (DerivedThis) thisObj;
        if (holder.value != null) {
            throw ScriptRuntime.referenceError(
                    ScriptRuntime.getMessageById("msg.class.super.twice"));
        }
        holder.value = result;
        return result;
    }

    /** SuperCall with the arguments collected into an array (spread arguments). */
    public static Object superCallArray(
            Object superConstructor,
            Object argsArray,
            Context cx,
            VarScope scope,
            Object thisObj,
            Object newTarget) {
        Object[] args;
        if (argsArray instanceof NativeArray) {
            NativeArray array = (NativeArray) argsArray;
            long length = array.getLength();
            args = new Object[(int) length];
            for (int i = 0; i < args.length; i++) {
                Object value = array.get(i, array);
                args[i] = value == Scriptable.NOT_FOUND ? Undefined.instance : value;
            }
        } else {
            throw Kit.codeBug();
        }
        return superCall(superConstructor, args, cx, scope, thisObj, newTarget);
    }

    /**
     * The result of [[Construct]] of a derived constructor: an object returned by the body, else
     * the bound this.
     */
    public static Scriptable derivedResult(Object result, Object thisObj) {
        if (result instanceof Scriptable) {
            return (Scriptable) result;
        }
        if (!Undefined.isUndefined(result)) {
            // Includes null, which is not an object either.
            throw ScriptRuntime.typeErrorById("msg.class.derived.return");
        }
        Scriptable value = ((DerivedThis) thisObj).value;
        if (value == null) {
            throw ScriptRuntime.referenceError(
                    ScriptRuntime.getMessageById("msg.class.this.uninit"));
        }
        return value;
    }

    /**
     * A reference to the class binding while the class is being defined, or to a parameter from the
     * default value of an earlier parameter.
     */
    public static RuntimeException tdzError(String name, boolean parameter) {
        String key = parameter ? "msg.param.tdz" : "msg.class.tdz";
        return ScriptRuntime.referenceError(ScriptRuntime.getMessageById(key, name));
    }

    /** Throws {@link #tdzError}; declared to return a value for the compiled code. */
    public static Object throwTdz(String name, boolean parameter) {
        throw tdzError(name, parameter);
    }

    /**
     * The rest element of an array binding pattern (IteratorBindingInitialization): an array of the
     * values the iterator has left.
     */
    public static Object iteratorRest(Object iterator, Context cx, VarScope scope) {
        var next = ScriptRuntime.getPropAndThis(iterator, ES6Iterator.NEXT_METHOD, cx, scope);
        List<Object> values = new ArrayList<>();
        for (; ; ) {
            Object result =
                    next.getCallable().call(cx, scope, next.getThis(), ScriptRuntime.emptyArgs);
            Scriptable record = ScriptableObject.ensureScriptable(result);
            Object done = ScriptableObject.getProperty(record, ES6Iterator.DONE_PROPERTY);
            if (done != Scriptable.NOT_FOUND && ScriptRuntime.toBoolean(done)) {
                break;
            }
            values.add(
                    ScriptRuntime.getObjectPropNoWarn(
                            result, ES6Iterator.VALUE_PROPERTY, cx, scope));
        }
        return cx.newArray(scope, values.toArray());
    }

    /**
     * IteratorClose of a for-of loop that ends before its iterator is done (break, return, an
     * exception). {@code quiet} is the exception case: errors of the {@code return} method are
     * ignored, and the result is false for the catch condition that rethrows the exception (see
     * {@link BindingLowering#forOf}).
     */
    public static Object iteratorClose(
            Object enumeration, boolean quiet, Context cx, VarScope scope) {
        if (enumeration instanceof ScriptRuntime.IdEnumeration) {
            ScriptRuntime.IdEnumeration x = (ScriptRuntime.IdEnumeration) enumeration;
            if (!x.done && x.iterator != null) {
                x.done = true;
                if (!quiet) {
                    closeIterator(x.iterator, cx, scope);
                } else {
                    try {
                        closeIterator(x.iterator, cx, scope);
                    } catch (RhinoException e) {
                        // The exception of the loop wins (IteratorClose, step 5).
                    }
                }
            }
        }
        return quiet ? Boolean.FALSE : Undefined.instance;
    }

    private static void closeIterator(Scriptable iterator, Context cx, VarScope scope) {
        Object method =
                ScriptRuntime.getObjectPropNoWarn(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
        if (method == null || Undefined.isUndefined(method)) {
            return;
        }
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(iterator, method, ES6Iterator.RETURN_PROPERTY);
        }
        Object result = ((Callable) method).call(cx, scope, iterator, ScriptRuntime.emptyArgs);
        if (!(result instanceof Scriptable)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(result));
        }
    }

    /**
     * The object of the with scope around the initialization of the parameters (see {@link
     * BindingLowering#paramScope}): {@code names} resolve in the scope that encloses the function,
     * not in the activation that also holds the declarations of the body.
     */
    public static Object paramScope(String names, Context cx, VarScope scope) {
        return new OuterNames(
                new HashSet<>(Arrays.asList(names.split(","))), scope.getParentScope());
    }

    /** See {@link #paramScope}. */
    static final class OuterNames extends ScriptableObject {
        private static final long serialVersionUID = 1L;

        private final Set<String> names;
        private final VarScope outer;

        OuterNames(Set<String> names, VarScope outer) {
            this.names = names;
            this.outer = outer;
        }

        @Override
        public String getClassName() {
            return "Object";
        }

        private VarScope binding(String name) {
            if (!names.contains(name)) {
                return null;
            }
            return ScriptRuntime.bind(Context.getCurrentContext(), outer, name);
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return binding(name) != null;
        }

        @Override
        public Object get(String name, Scriptable start) {
            VarScope binding = binding(name);
            return binding == null ? NOT_FOUND : ScriptableObject.getProperty(binding, name);
        }

        @Override
        public void put(String name, Scriptable start, Object value) {
            VarScope binding = binding(name);
            if (binding != null) {
                ScriptableObject.putProperty(binding, name, value);
            }
        }
    }

    /** RequireObjectCoercible of the value bound to an empty object pattern. */
    public static Object requireObjectCoercible(Object value) {
        if (value == null) {
            throw ScriptRuntime.typeErrorById("msg.null.to.object");
        }
        if (Undefined.isUndefined(value)) {
            throw ScriptRuntime.typeErrorById("msg.undef.to.object");
        }
        return value;
    }

    /**
     * OrdinaryCreateFromConstructor for an ordinary object (ROADMAP T9): an object whose
     * [[Prototype]] is {@code newTarget.prototype}, or {@code defaultProto} when that is not an
     * object.
     */
    public static Scriptable createFromConstructor(
            Object newTarget, VarScope scope, Scriptable defaultProto) {
        NativeObject result = new NativeObject();
        result.setPrototype(prototypeFromConstructor(newTarget, defaultProto));
        result.setParentScope(scope);
        return result;
    }

    /** GetPrototypeFromConstructor (ROADMAP T9). */
    static Scriptable prototypeFromConstructor(Object newTarget, Scriptable defaultProto) {
        if (newTarget instanceof BaseFunction) {
            Object proto = ((BaseFunction) newTarget).getPrototypeProperty();
            if (proto instanceof Scriptable) {
                return (Scriptable) proto;
            }
        } else if (newTarget instanceof Scriptable) {
            Object proto = ScriptableObject.getProperty((Scriptable) newTarget, "prototype");
            if (proto instanceof Scriptable) {
                return (Scriptable) proto;
            }
        }
        return defaultProto;
    }

    private static String describe(Object value) {
        if (value instanceof Symbol) {
            return value.toString();
        }
        if (value instanceof BaseFunction) {
            String name = ((BaseFunction) value).getFunctionName();
            return name.isEmpty() ? "function" : name;
        }
        if (value instanceof Scriptable) {
            return "[object " + ((Scriptable) value).getClassName() + "]";
        }
        return ScriptRuntime.toString(value);
    }
}
