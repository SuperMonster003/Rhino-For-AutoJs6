/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mozilla.javascript.autojs6.JavaClassInheritance; // AutoJs6: class syntax

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
            heritage = JavaClassInheritance.heritage(heritage, cx, scope); // AutoJs6: class syntax
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
        int kind = flags & ClassOp.ELEMENT_KIND_MASK;
        if (kind == ClassOp.ELEMENT_FIELD) {
            defineField(f, home, key, method, flags, cx, scope);
            return f;
        }
        if ((flags & ClassOp.ELEMENT_PRIVATE) != 0) {
            definePrivateMethod(f, home, (PrivateNameKey) key, method, flags, cx, scope);
            return f;
        }
        Object id = toPropertyKey(key);
        JSFunction fn = newMethod(home, method, cx, scope);
        if ((flags & ClassOp.ELEMENT_COMPUTED) != 0) {
            String prefix =
                    kind == ClassOp.ELEMENT_GETTER
                            ? "get "
                            : kind == ClassOp.ELEMENT_SETTER ? "set " : "";
            fn.setFunctionName(prefix + functionName(id));
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

    /** A method of a class with its home object, a generator method with its prototype. */
    private static JSFunction newMethod(
            ScriptableObject home, JSDescriptor<JSFunction> method, Context cx, VarScope scope) {
        JSFunction fn = new JSFunction(cx, scope, method, null, Undefined.instance, home);
        removeLegacyProperties(fn);
        if (method.isES6Generator() && method.asyncKind() != AsyncRuntime.ASYNC_FUNCTION) {
            // A generator method gets a fresh prototype for its generator objects, without a
            // constructor property (MethodDefinitionEvaluation of a GeneratorMethod).
            NativeObject generatorProto = new NativeObject();
            generatorProto.setParentScope(scope);
            VarScope top = ScriptableObject.getTopLevelScope(scope);
            Object parent = ScriptableObject.getTopScopeValue(top, AsyncRuntime.generatorTag(fn));
            if (parent instanceof Scriptable) {
                generatorProto.setPrototype((Scriptable) parent);
            }
            fn.setPrototypePropertyAttributes(
                    ScriptableObject.DONTENUM | ScriptableObject.PERMANENT);
            fn.setPrototypeProperty(generatorProto);
        }
        return fn;
    }

    // ------------------------------------------------------------------
    // Fields, static blocks and private names (ROADMAP P3)
    // ------------------------------------------------------------------

    /**
     * The internal slots of a class constructor F that the definition fills in: [[Fields]], the
     * brands of [[PrivateMethods]] and of the static private methods, and the static elements that
     * {@link #staticInit} runs once.
     */
    static final class ClassData implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        final List<FieldRecord> instanceFields = new ArrayList<>();
        List<Object> staticElements = new ArrayList<>();
        PrivateNameKey instanceBrand;
        PrivateNameKey staticBrand;
        final String className;

        ClassData(String className) {
            this.className = className;
        }

        @Serial
        private void readObject(ObjectInputStream stream)
                throws IOException, ClassNotFoundException {
            Context.checkDeserializationEnabled();
            stream.defaultReadObject();
        }

        @Serial
        private void writeObject(ObjectOutputStream stream) throws IOException {
            Context.checkSerializationEnabled();
            stream.defaultWriteObject();
        }

        @Serial
        private void readObjectNoData() throws InvalidObjectException {
            throw new InvalidObjectException("Missing serialized Rhino object data");
        }
    }

    /** A ClassFieldDefinition Record: the key (a property key or private name), the initializer. */
    static final class FieldRecord implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        final Object key;
        final JSFunction initializer;

        /** The initializer is an anonymous function definition named by a computed key. */
        final boolean named;

        FieldRecord(Object key, JSFunction initializer, boolean named) {
            this.key = key;
            this.initializer = initializer;
            this.named = named;
        }

        @Serial
        private void readObject(ObjectInputStream stream)
                throws IOException, ClassNotFoundException {
            Context.checkDeserializationEnabled();
            stream.defaultReadObject();
        }

        @Serial
        private void writeObject(ObjectOutputStream stream) throws IOException {
            Context.checkSerializationEnabled();
            stream.defaultWriteObject();
        }

        @Serial
        private void readObjectNoData() throws InvalidObjectException {
            throw new InvalidObjectException("Missing serialized Rhino object data");
        }
    }

    private static ClassData classData(JSFunction f) {
        ClassData data = f.classData;
        if (data == null) {
            String name = f.getFunctionName();
            data = new ClassData(name == null || name.isEmpty() ? "anonymous" : name);
            f.classData = data;
        }
        return data;
    }

    /** A new private name, for the hidden binding {@code #x@n} of a class scope. */
    public static Object newPrivateName(String description) {
        return new PrivateNameKey(description);
    }

    /**
     * Annex B.3.3.1: the evaluation of a function declaration of a switch, whose block binding the
     * entry to the switch initialized (ClassOp.BLOCK_FUNCTION). {@code scope} is the block.
     */
    public static Object hoistBlockFunction(VarScope scope, String name) {
        Object value = scope.get(name, scope);
        while (scope.isNestedScope()) {
            scope = scope.getParentScope();
        }
        scope.put(name, scope, value);
        return Undefined.instance;
    }

    /**
     * ClassFieldDefinitionEvaluation and ClassStaticBlockDefinitionEvaluation: converts the key and
     * creates the initializer (or the body of the static block) with its home object, and records
     * the element on F.
     */
    private static void defineField(
            JSFunction f,
            ScriptableObject home,
            Object key,
            JSDescriptor<JSFunction> initializer,
            int flags,
            Context cx,
            VarScope scope) {
        ClassData data = classData(f);
        JSFunction fn =
                initializer == null
                        ? null
                        : new JSFunction(cx, scope, initializer, null, Undefined.instance, home);
        if ((flags & ClassOp.ELEMENT_BLOCK) != 0) {
            data.staticElements.add(fn);
            return;
        }
        Object id = (flags & ClassOp.ELEMENT_PRIVATE) != 0 ? key : toPropertyKey(key);
        FieldRecord field = new FieldRecord(id, fn, (flags & ClassOp.ELEMENT_NAMED) != 0);
        if ((flags & ClassOp.ELEMENT_STATIC) != 0) {
            data.staticElements.add(field);
        } else {
            data.instanceFields.add(field);
        }
    }

    /**
     * ClassElementEvaluation of a private method or accessor: the function goes on the private
     * name, and the class brand that {@link #initializeInstanceElements} (or {@link #staticInit})
     * adds to the objects it applies to.
     */
    private static void definePrivateMethod(
            JSFunction f,
            ScriptableObject home,
            PrivateNameKey key,
            JSDescriptor<JSFunction> method,
            int flags,
            Context cx,
            VarScope scope) {
        ClassData data = classData(f);
        JSFunction fn = newMethod(home, method, cx, scope);
        if ((flags & ClassOp.ELEMENT_STATIC) != 0) {
            if (data.staticBrand == null) {
                data.staticBrand = PrivateNameKey.newBrand(data.className);
            }
            key.brand = data.staticBrand;
        } else {
            if (data.instanceBrand == null) {
                data.instanceBrand = PrivateNameKey.newBrand(data.className);
            }
            key.brand = data.instanceBrand;
        }
        switch (flags & ClassOp.ELEMENT_KIND_MASK) {
            case ClassOp.ELEMENT_GETTER:
                key.kind = PrivateNameKey.ACCESSOR;
                key.getter = fn;
                break;
            case ClassOp.ELEMENT_SETTER:
                key.kind = PrivateNameKey.ACCESSOR;
                key.setter = fn;
                break;
            default:
                key.kind = PrivateNameKey.METHOD;
                key.method = fn;
                break;
        }
    }

    /**
     * InitializeInstanceElements: adds the brand of the private methods of the class F, then
     * defines its fields on the new object. Called at the top of a base constructor ({@link
     * ClassOp#INIT_FIELDS}) and after {@code super()} returns in a derived one.
     */
    public static Object initializeInstanceElements(Object thisObj, Object f, Context cx) {
        ClassData data = ((JSFunction) f).classData;
        if (data != null) {
            Scriptable receiver = (Scriptable) thisObj;
            VarScope scope = ((JSFunction) f).getDeclarationScope();
            if (data.instanceBrand != null) {
                addBrand(receiver, data.instanceBrand, cx, scope);
            }
            for (FieldRecord field : data.instanceFields) {
                defineField(receiver, field, cx, scope);
            }
        }
        return Undefined.instance;
    }

    /**
     * The end of ClassDefinitionEvaluation, once the class binding is initialized: adds the brand
     * of the static private methods to F, then runs the static fields and static blocks in order.
     * Returns F.
     */
    public static Object staticInit(Object classConstructor, Context cx) {
        JSFunction f = (JSFunction) classConstructor;
        ClassData data = f.classData;
        if (data == null) {
            return f;
        }
        VarScope scope = f.getDeclarationScope();
        if (data.staticBrand != null) {
            addBrand(f, data.staticBrand, cx, scope);
        }
        List<Object> elements = data.staticElements;
        data.staticElements = null;
        for (Object element : elements) {
            if (element instanceof FieldRecord) {
                defineField(f, (FieldRecord) element, cx, scope);
            } else {
                JSFunction block = (JSFunction) element;
                block.call(cx, block.getDeclarationScope(), f, ScriptRuntime.emptyArgs);
            }
        }
        return f;
    }

    /** DefineField. */
    private static void defineField(
            Scriptable receiver, FieldRecord field, Context cx, VarScope scope) {
        Object value = Undefined.instance;
        if (field.initializer != null) {
            JSFunction init = field.initializer;
            value = init.call(cx, init.getDeclarationScope(), receiver, ScriptRuntime.emptyArgs);
            if (field.named) {
                nameAnonymous(value, field.key);
            }
        }
        if (field.key instanceof PrivateNameKey) {
            PrivateNameKey key = (PrivateNameKey) field.key;
            if (!PrivateElements.add(receiver, key, value)) {
                throw privateError(cx, scope, "msg.class.private.twice", key);
            }
            return;
        }
        createDataPropertyOrThrow(receiver, field.key, value, cx);
    }

    /** NamedEvaluation of an anonymous function or class definition with a computed key. */
    private static void nameAnonymous(Object value, Object key) {
        if (value instanceof BaseFunction
                && "".equals(ScriptableObject.getProperty((BaseFunction) value, "name"))) {
            ((BaseFunction) value).setFunctionName(functionName(key));
        }
    }

    /** CreateDataPropertyOrThrow, for a public field. */
    private static void createDataPropertyOrThrow(
            Scriptable receiver, Object key, Object value, Context cx) {
        if (receiver instanceof ScriptableObject) {
            ScriptableObject.DescriptorInfo desc =
                    new ScriptableObject.DescriptorInfo(true, true, true, value);
            if (!((ScriptableObject) receiver).defineOwnProperty(cx, key, desc)) {
                throw ScriptRuntime.typeErrorById(
                        "msg.class.field.define", ScriptRuntime.toString(key));
            }
        } else if (key instanceof Symbol) {
            ScriptableObject.putProperty(receiver, (Symbol) key, value);
        } else {
            ScriptRuntime.StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
            if (s.stringId == null) {
                ScriptableObject.putProperty(receiver, s.index, value);
            } else {
                ScriptableObject.putProperty(receiver, s.stringId, value);
            }
        }
    }

    /** PrivateMethodOrAccessorAdd for all the private methods of a class at once. */
    private static void addBrand(
            Scriptable receiver, PrivateNameKey brand, Context cx, VarScope scope) {
        if (!PrivateElements.add(receiver, brand, Boolean.TRUE)) {
            throw privateError(cx, scope, "msg.class.private.methods.twice", brand);
        }
    }

    /**
     * The TypeError of a failed private element operation, an object of the realm of the code that
     * runs it (the scope of the access, or of the class for an initialization): an EcmaError would
     * become an error of the realm that catches it.
     */
    private static JavaScriptException privateError(
            Context cx, VarScope scope, String messageId, PrivateNameKey key) {
        String message = ScriptRuntime.getMessageById(messageId, key.toString());
        return ScriptRuntime.throwCustomError(cx, scope, "TypeError", message);
    }

    /** ToObject of the base of a private reference. */
    private static Scriptable privateBase(
            Object obj, PrivateNameKey key, Context cx, VarScope scope, boolean write) {
        Scriptable base = ScriptRuntime.toObjectOrNull(cx, obj, scope);
        if (base == null) {
            throw write
                    ? ScriptRuntime.undefWriteError(obj, key, Undefined.instance)
                    : ScriptRuntime.undefReadError(obj, key);
        }
        return base;
    }

    /** PrivateElementFind for a key of the object: whether it has the field or the brand. */
    private static boolean hasPrivate(Object object, PrivateNameKey key) {
        return PrivateElements.has(object, key.kind == PrivateNameKey.FIELD ? key : key.brand);
    }

    /** {@code o.#x}: PrivateGet. */
    public static Object privateGet(Object obj, Object name, Context cx, VarScope scope) {
        PrivateNameKey key = (PrivateNameKey) name;
        Scriptable base = privateBase(obj, key, cx, scope, false);
        return privateGet(base, key, cx, scope);
    }

    private static Object privateGet(
            Scriptable base, PrivateNameKey key, Context cx, VarScope scope) {
        if (key.kind == PrivateNameKey.FIELD) {
            PrivateElements elements = PrivateElements.of(base);
            int index = elements == null ? -1 : elements.indexOf(key);
            if (index < 0) {
                throw privateError(cx, scope, "msg.class.private.read", key);
            }
            return elements.values[index];
        }
        if (!PrivateElements.has(base, key.brand)) {
            throw privateError(cx, scope, "msg.class.private.read", key);
        }
        if (key.kind == PrivateNameKey.METHOD) {
            return key.method;
        }
        if (key.getter == null) {
            throw privateError(cx, scope, "msg.class.private.no.getter", key);
        }
        return ((Callable) key.getter).call(cx, scope, base, ScriptRuntime.emptyArgs);
    }

    /** {@code o.#x = value}: PrivateSet. Returns the value. */
    public static Object privateSet(
            Object obj, Object name, Object value, Context cx, VarScope scope) {
        PrivateNameKey key = (PrivateNameKey) name;
        Scriptable base = privateBase(obj, key, cx, scope, true);
        if (key.kind == PrivateNameKey.FIELD) {
            PrivateElements elements = PrivateElements.of(base);
            int index = elements == null ? -1 : elements.indexOf(key);
            if (index < 0) {
                throw privateError(cx, scope, "msg.class.private.write", key);
            }
            elements.values[index] = value;
            return value;
        }
        if (!PrivateElements.has(base, key.brand)) {
            throw privateError(cx, scope, "msg.class.private.write", key);
        }
        if (key.kind == PrivateNameKey.METHOD) {
            throw privateError(cx, scope, "msg.class.private.method.write", key);
        }
        if (key.setter == null) {
            throw privateError(cx, scope, "msg.class.private.no.setter", key);
        }
        ((Callable) key.setter).call(cx, scope, base, new Object[] {value});
        return value;
    }

    /** {@code o.#m(...)}: the value of the private member and the object as the this value. */
    public static ScriptRuntime.LookupResult privateCall(
            Object obj, Object name, Context cx, VarScope scope, boolean optional) {
        PrivateNameKey key = (PrivateNameKey) name;
        Scriptable base = ScriptRuntime.toObjectOrNull(cx, obj, scope);
        if (base == null) {
            // As getPropAndThisOptional does for o?.m(): the optional chain ends.
            if (optional) {
                return null;
            }
            throw ScriptRuntime.undefCallError(obj, key);
        }
        Object value = privateGet(base, key, cx, scope);
        if (optional && (value == null || Undefined.isUndefined(value))) {
            return null;
        }
        return new ScriptRuntime.LookupResult(value, base, key.toString());
    }

    /** The value that {@code ?.()} tests: the function of the lookup, undefined when absent. */
    public static Object optionalCallee(ScriptRuntime.LookupResult lookup) {
        Object value = lookup.getResult();
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    /** {@code #x in o}. */
    public static boolean privateIn(Object name, Object obj) {
        if (!(obj instanceof Scriptable)) {
            throw ScriptRuntime.typeErrorById("msg.in.not.object");
        }
        return hasPrivate(obj, (PrivateNameKey) name);
    }

    /**
     * What the parser of direct eval code needs to know about the code that calls eval (ROADMAP
     * appendix C Q13): the private names visible there, and the kind of class element the eval is
     * called from, whose rules for {@code arguments} and {@code new.target} the eval code follows.
     */
    static final class EvalContext {
        /** {@link ClassParser#FN_METHOD}, {@code FN_FIELD}, {@code FN_STATIC_BLOCK} or -1. */
        final int kind;

        /** The identifier of each visible private name to its hidden binding, innermost first. */
        final Map<String, String> privateNames;

        EvalContext(int kind, Map<String, String> privateNames) {
            this.kind = kind;
            this.privateNames = privateNames;
        }

        String privateBinding(String identifier) {
            return privateNames.get(identifier);
        }
    }

    /**
     * The context of a direct eval called in {@code scope}, or {@code null} when nothing about it
     * concerns classes. The function that calls eval is the nearest activation of a function other
     * than an arrow function (block scopes and arrow functions are transparent); the private names
     * are the hidden bindings of the class scopes on the scope chain, which are only looked for
     * when the source mentions {@code #}.
     */
    static EvalContext evalContext(VarScope scope, String source) {
        int kind = -1;
        for (VarScope s = scope; s != null; s = s.getParentScope()) {
            if (s instanceof NativeCall) {
                JSDescriptor<JSFunction> descriptor = ((NativeCall) s).function.getDescriptor();
                if (descriptor.hasLexicalThis()) {
                    continue;
                }
                kind = descriptor.classElement;
                break;
            }
            if (!(s instanceof LocalScope) && s.getClass() != ScopeObject.class) {
                // Block scopes and the variable scope of strict eval code are transparent.
                break;
            }
        }
        Map<String, String> names = Map.of();
        if (source.indexOf('#') >= 0) {
            names = new HashMap<>();
            for (VarScope s = scope; s != null; s = s.getParentScope()) {
                if (!(s instanceof LocalScope)) {
                    continue;
                }
                for (Object id : s.getIds()) {
                    if (id instanceof String && ClassParser.isPrivateBinding((String) id)) {
                        names.putIfAbsent(ClassParser.privateDescription((String) id), (String) id);
                    }
                }
            }
        }
        if (kind < 0 && names.isEmpty()) {
            return null;
        }
        return new EvalContext(kind, names);
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
                throw uninitializedThis(((DerivedThis) thisObj).constructor);
            }
            return value;
        }
        return thisObj;
    }

    /** The ReferenceError of reading {@code this} before {@code super()} has initialized it. */
    private static EcmaError uninitializedThis(JSFunction constructor) {
        String key = "msg.class.this.uninit";
        String java = JavaClassInheritance.superclassName(constructor); // AutoJs6: class syntax
        if (java != null) key = "msg.java.class.this.uninit"; // AutoJs6: class syntax
        return ScriptRuntime.referenceError(ScriptRuntime.getMessageById(key, java));
    }

    /**
     * Returns the this value an operation on the current function's this (a super property access)
     * works on: like reading {@code this}, it fails before {@code super()}.
     */
    public static Object receiver(Object thisObj) {
        return thisValue(thisObj);
    }

    /**
     * The home object of the function running in the scope, for {@code super} in compiled code. The
     * scope is the activation of the function or, inside a let block or a with statement of it, a
     * scope whose parents lead to the activation; the upstream code only expected the activation.
     */
    public static Scriptable homeObject(Object scope) {
        VarScope s = (VarScope) scope;
        while (!(s instanceof NativeCall)) {
            s = s.getParentScope();
        }
        return ((NativeCall) s).getHomeObject();
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
        DerivedThis holder = (DerivedThis) thisObj;
        if (holder.value != null) {
            throw ScriptRuntime.referenceError(
                    ScriptRuntime.getMessageById("msg.class.super.twice"));
        }
        holder.value = result;
        initializeInstanceElements(result, holder.constructor, cx);
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
            throw uninitializedThis(((DerivedThis) thisObj).constructor);
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

    /**
     * The read of a compound assignment from the scope {@code bound} that its BINDNAME found, see
     * {@link ClassOp#markBoundRead}.
     */
    public static Object boundValue(Object bound, Context cx, VarScope scope, String name) {
        return OrdinaryHas.boundValue(bound, cx, scope, name);
    }

    /** The binding of a logical assignment to a name, see {@link ClassOp#LOGICAL_NAME}. */
    public static Object logicalBind(Context cx, VarScope scope, String name) {
        return OrdinaryHas.logicalBind(cx, scope, name);
    }

    /** The scope that the write of a logical assignment to a name goes to. */
    public static VarScope logicalTarget(Object bound, Context cx, VarScope scope, String name) {
        return OrdinaryHas.logicalTarget(bound, cx, scope, name);
    }

    /**
     * The put of a TypedArray for a receiver other than itself (ROADMAP P6.17, appendix C Q43), see
     * {@link OrdinarySet#typedArrayPut}: true when it handled the assignment.
     */
    public static boolean typedArrayPut(Scriptable ta, Scriptable start, int index, Object value) {
        return OrdinarySet.typedArrayPut(ta, start, index, value);
    }

    /** {@link #typedArrayPut(Scriptable, Scriptable, int, Object)} with a String key. */
    public static boolean typedArrayPut(
            Scriptable ta, Scriptable start, String name, Object value) {
        return OrdinarySet.typedArrayPut(ta, start, name, value);
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
