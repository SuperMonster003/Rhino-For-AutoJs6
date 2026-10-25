/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.typedarrays.NativeArrayBuffer;

/**
 * AutoJs6 class syntax: built-in constructors create their instances from new.target (ROADMAP P4,
 * D2, T9). This is GetPrototypeFromConstructor with GetFunctionRealm, OrdinaryCreateFromConstructor
 * for ordinary objects, the construction paths of {@link BaseFunction}, {@link JSFunction} and
 * {@link LambdaConstructor}, and the parts of built-in constructors that run on the new object: the
 * adder of the Map and Set constructors, the checks of the DataView constructor after the prototype
 * is read, the RegExp constructor called as a function and RegExpExec in {@code test}.
 *
 * <p>The intrinsic default prototype of a built-in constructor is its own {@code prototype}; when
 * new.target belongs to another realm, it is the prototype of the constructor of the same name
 * there.
 *
 * <p>The methods are public for the built-ins of other packages; they are not an API.
 */
public final class BuiltinConstruction {

    private BuiltinConstruction() {}

    /**
     * GetPrototypeFromConstructor(newTarget, the intrinsic default prototype of {@code f}) for the
     * built-in constructor {@code f}. {@code new f()} and calls (no new.target) take the prototype
     * of {@code f} without a lookup.
     */
    public static Scriptable prototypeOf(BaseFunction f, Object newTarget) {
        return prototypeOf(f, newTarget, null);
    }

    /**
     * {@link #prototypeOf(BaseFunction, Object)} with the intrinsic default prototype {@code type}
     * (a {@link TopLevel.Builtins} or {@link TopLevel.NativeErrors}) for an {@code f} that is not
     * named like a built-in: a static method such as {@code Uint8Array.fromBase64} runs the
     * constructor code with itself as {@code f}.
     */
    public static Scriptable prototypeOf(BaseFunction f, Object newTarget, Enum<?> type) {
        Object proto;
        if (newTarget == f || newTarget == null || Undefined.isUndefined(newTarget)) {
            proto = f.getPrototypeProperty();
            if (proto instanceof Scriptable) {
                return (Scriptable) proto;
            }
            newTarget = f;
        } else {
            proto = prototypeProperty(newTarget);
            if (proto instanceof Scriptable) {
                return (Scriptable) proto;
            }
        }
        TopLevel own = realmOf(f, null);
        TopLevel realm = functionRealm(newTarget, own);
        Object ownProto = f.getPrototypeProperty();
        if (realm == own && ownProto instanceof Scriptable) {
            return (Scriptable) ownProto;
        }
        Scriptable intrinsic = intrinsicPrototype(realm, f, type);
        if (intrinsic != null) {
            return intrinsic;
        }
        if (ownProto instanceof Scriptable || realm == null) {
            return ownProto instanceof Scriptable ? (Scriptable) ownProto : null;
        }
        return ScriptableObject.getObjectPrototype(realm);
    }

    /**
     * OrdinaryCreateFromConstructor(newTarget, defaultProto) for an ordinary object, the this value
     * of a base constructor: the prototype is {@code newTarget.prototype}, or {@code defaultProto}
     * of the realm of new.target. {@code scope} becomes the parent scope and gives the current
     * realm.
     */
    public static Scriptable createFromConstructor(
            Context cx, Object newTarget, VarScope scope, TopLevel.Builtins defaultProto) {
        Object proto = prototypeProperty(newTarget);
        NativeObject result = new NativeObject();
        if (proto instanceof Scriptable) {
            result.setPrototype((Scriptable) proto);
        } else {
            TopLevel realm = functionRealm(newTarget, ScriptableObject.getTopLevelScope(scope));
            result.setPrototype(TopLevel.getBuiltinPrototype(realm, defaultProto));
        }
        result.setParentScope(scope);
        return result;
    }

    /**
     * The this object that {@code JSFunction.construct} hands to the constructor code. Script
     * functions get OrdinaryCreateFromConstructor. Built-in constructors create their own instance
     * and ignore this one, so it is made as before P4, without reading the prototype of new.target
     * (an observable Get, test262 checks that some arguments are validated before it).
     */
    static Scriptable thisObject(JSFunction f, Context cx, VarScope scope, Object newTarget) {
        if (f.getDescriptor().isFunction()) {
            return f.createObject(cx, scope, newTarget);
        }
        NativeObject obj = new NativeObject();
        Object proto =
                newTarget instanceof JSFunction
                        ? ((JSFunction) newTarget).getPrototypeProperty()
                        : null;
        obj.setPrototype(proto instanceof Scriptable ? (Scriptable) proto : f.getClassPrototype());
        obj.setParentScope(f.getParentScope());
        return obj;
    }

    /**
     * [[Construct]] of a {@link BaseFunction} that is not a {@link JSFunction}: {@link
     * BaseFunction#createObject(Context, VarScope)} with the prototype of new.target, before the
     * function body runs; {@code null} when the function creates the object itself.
     */
    static Scriptable create(BaseFunction f, Context cx, VarScope scope, Object newTarget) {
        Scriptable result = f.createObject(cx, scope);
        return result == null ? null : withPrototype(f, result, newTarget);
    }

    /**
     * Gives an object a host constructor created (a {@link LambdaConstructor}, a {@link
     * FunctionObject} that creates its instances in its body) the prototype of new.target. The Java
     * code does not see new.target; the object is new and not yet visible to scripts. Without
     * another new.target the object keeps the prototype the constructor gave it.
     */
    public static Scriptable withPrototype(BaseFunction f, Scriptable obj, Object newTarget) {
        if (newTarget == f || newTarget == null || Undefined.isUndefined(newTarget)) {
            return obj;
        }
        Object proto = prototypeProperty(newTarget);
        if (proto instanceof Scriptable) {
            obj.setPrototype((Scriptable) proto);
            return obj;
        }
        TopLevel own = realmOf(f, null);
        TopLevel realm = functionRealm(newTarget, own);
        Scriptable intrinsic = realm == own ? null : intrinsicPrototype(realm, f, null);
        if (intrinsic != null) {
            obj.setPrototype(intrinsic);
        }
        return obj;
    }

    /**
     * The {@code constructor} of the prototype object a function gets: none for a generator
     * function, whose prototype only inherits from %GeneratorPrototype%.
     */
    static void defineConstructor(NativeObject proto, BaseFunction f) {
        if (!f.isGeneratorFunction()) {
            proto.defineProperty("constructor", f, ScriptableObject.DONTENUM);
        }
    }

    /** Get(newTarget, "prototype"); the prototype of a JSFunction without a lookup. */
    private static Object prototypeProperty(Object newTarget) {
        if (newTarget instanceof JSFunction) {
            return ((JSFunction) newTarget).getPrototypeProperty();
        }
        if (newTarget instanceof Scriptable) {
            return ScriptableObject.getProperty((Scriptable) newTarget, "prototype");
        }
        return Undefined.instance;
    }

    /**
     * GetFunctionRealm: the realm of the target of a bound function or a proxy (a TypeError for a
     * revoked proxy), else of the declaration scope; {@code current} when there is none.
     */
    static TopLevel functionRealm(Object fn, TopLevel current) {
        Object obj = fn;
        while (true) {
            if (obj instanceof BoundFunction) {
                obj = ((BoundFunction) obj).getTargetFunction();
            } else if (obj instanceof NativeProxy) {
                obj = ((NativeProxy) obj).getTargetThrowIfRevoked();
            } else {
                break;
            }
        }
        if (obj instanceof Function) {
            return realmOf((Function) obj, current);
        }
        return current;
    }

    private static TopLevel realmOf(Function f, TopLevel fallback) {
        VarScope scope = f.getDeclarationScope();
        return scope == null ? fallback : ScriptableObject.getTopLevelScope(scope);
    }

    /**
     * The prototype of the built-in constructor named like {@code f} in {@code realm}, else of
     * {@code type}, else of the class named like {@code f}; null when there is none.
     */
    private static Scriptable intrinsicPrototype(TopLevel realm, BaseFunction f, Enum<?> type) {
        if (realm == null) {
            return null;
        }
        String name = f.getFunctionName();
        for (TopLevel.Builtins builtin : TopLevel.Builtins.values()) {
            if (builtin.name().equals(name)) {
                return TopLevel.getBuiltinPrototype(realm, builtin);
            }
        }
        for (TopLevel.NativeErrors error : TopLevel.NativeErrors.values()) {
            if (error.name().equals(name)) {
                return TopLevel.getBuiltinPrototype(realm, error);
            }
        }
        if (type instanceof TopLevel.Builtins) {
            return TopLevel.getBuiltinPrototype(realm, (TopLevel.Builtins) type);
        }
        if (type instanceof TopLevel.NativeErrors) {
            return TopLevel.getBuiltinPrototype(realm, (TopLevel.NativeErrors) type);
        }
        if (name.isEmpty()) {
            return null;
        }
        return ScriptableObject.getClassPrototype(realm, name);
    }

    /**
     * The entries of the Map and WeakMap constructors (AddEntriesFromIterable): the adder is the
     * {@code set} method of the new object, which already has the prototype of new.target.
     */
    static void addEntriesFromIterable(
            Context cx, VarScope scope, Scriptable target, Object iterable) {
        if (iterable == null || Undefined.isUndefined(iterable)) {
            return;
        }
        Callable adder = adder(target, "set");
        Object iterator = ScriptRuntime.callIterator(iterable, cx, scope);
        if (Undefined.isUndefined(iterator)) {
            return;
        }
        try (IteratorLikeIterable it = new IteratorLikeIterable(cx, scope, iterator)) {
            for (Object item : it) {
                Scriptable entry = ScriptableObject.ensureScriptable(item);
                if (entry instanceof Symbol) {
                    throw ScriptRuntime.typeErrorById(
                            "msg.arg.not.object", ScriptRuntime.species(entry));
                }
                Object key = ScriptableObject.getProperty(entry, 0);
                Object value = ScriptableObject.getProperty(entry, 1);
                adder.call(
                        cx,
                        scope,
                        target,
                        new Object[] {
                            key == Scriptable.NOT_FOUND ? Undefined.instance : key,
                            value == Scriptable.NOT_FOUND ? Undefined.instance : value
                        });
            }
        }
    }

    /**
     * The values of the Set and WeakSet constructors: the adder is the {@code add} method of the
     * new object, which already has the prototype of new.target.
     */
    static void addValuesFromIterable(
            Context cx, VarScope scope, Scriptable target, Object iterable) {
        if (iterable == null || Undefined.isUndefined(iterable)) {
            return;
        }
        Callable adder = adder(target, "add");
        Object iterator = ScriptRuntime.callIterator(iterable, cx, scope);
        if (Undefined.isUndefined(iterator)) {
            return;
        }
        try (IteratorLikeIterable it = new IteratorLikeIterable(cx, scope, iterator)) {
            for (Object value : it) {
                adder.call(
                        cx,
                        scope,
                        target,
                        new Object[] {value == Scriptable.NOT_FOUND ? Undefined.instance : value});
            }
        }
    }

    private static Callable adder(Scriptable target, String name) {
        Object adder = ScriptableObject.getProperty(target, name);
        if (!(adder instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(adder, name);
        }
        return (Callable) adder;
    }

    /**
     * DataView ( buffer, byteOffset, byteLength ) steps 11 to 14, after the prototype of new.target
     * is read: the buffer may have been detached or resized. {@code length} is -1 without a
     * byteLength argument.
     */
    public static void checkView(NativeArrayBuffer buffer, int offset, int length) {
        if (buffer.isDetached()) {
            throw ScriptRuntime.typeErrorById("msg.arraybuf.detached");
        }
        int bufferByteLength = buffer.getLength();
        if (offset > bufferByteLength) {
            throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range");
        }
        if (length >= 0 && (long) offset + length > bufferByteLength) {
            throw ScriptRuntime.rangeErrorById("msg.dataview.length.range");
        }
    }

    /**
     * RegExp ( pattern, flags ) steps 1 to 6: returns {@code null} when the call returns the
     * pattern itself (no new.target, a regular expression or an object whose {@code Symbol.match}
     * is truthy, no flags, and its {@code constructor} is {@code f}), else the source and flags to
     * initialize the new object with. {@code hasMatcher} tells whether the pattern is a RegExp
     * object, whose source and flags the compilation takes itself.
     */
    public static Object[] regExpArgs(
            BaseFunction f, Object newTarget, Object[] args, boolean hasMatcher) {
        boolean called = Undefined.isUndefined(newTarget);
        Object pattern = args.length > 0 ? args[0] : Undefined.instance;
        Object flags = args.length > 1 ? args[1] : Undefined.instance;
        boolean isRegExp = isRegExp(pattern, hasMatcher);
        if (called && isRegExp && Undefined.isUndefined(flags)) {
            Object constructor = ScriptableObject.getProperty((Scriptable) pattern, "constructor");
            if (constructor == f) {
                return null;
            }
        }
        if (isRegExp && !hasMatcher) {
            Scriptable like = (Scriptable) pattern;
            Object source = ScriptableObject.getProperty(like, "source");
            if (Undefined.isUndefined(flags)) {
                flags = ScriptableObject.getProperty(like, "flags");
            }
            return new Object[] {
                source == Scriptable.NOT_FOUND ? Undefined.instance : source,
                flags == Scriptable.NOT_FOUND ? Undefined.instance : flags
            };
        }
        return args;
    }

    /** IsRegExp: {@code Symbol.match} decides when it is defined. */
    private static boolean isRegExp(Object value, boolean hasMatcher) {
        if (!(value instanceof Scriptable) || value instanceof Symbol) {
            return false;
        }
        Object matcher = ScriptableObject.getProperty((Scriptable) value, SymbolKey.MATCH);
        if (matcher != Scriptable.NOT_FOUND && !Undefined.isUndefined(matcher)) {
            return ScriptRuntime.toBoolean(matcher);
        }
        return hasMatcher;
    }

    /**
     * RegExp.prototype.test with an {@code exec} other than the built-in one (RegExpExec): a
     * callable that runs the test with it, or {@code null} for the built-in exec (or none, which
     * fails like the built-in one).
     */
    public static Callable userTest(Object thisObj, JSDescriptor<JSFunction> builtinExec) {
        if (!(thisObj instanceof Scriptable)) {
            return null;
        }
        Object exec = ScriptableObject.getProperty((Scriptable) thisObj, "exec");
        if (!(exec instanceof Callable)
                || (exec instanceof JSFunction
                        && ((JSFunction) exec).getDescriptor() == builtinExec)) {
            return null;
        }
        return (cx, scope, r, args) -> {
            String string = ScriptRuntime.toString(args.length > 0 ? args[0] : Undefined.instance);
            Object result = ((Callable) exec).call(cx, scope, r, new Object[] {string});
            if (result != null && !(result instanceof Scriptable)) {
                throw ScriptRuntime.typeErrorById(
                        "msg.arg.not.object", ScriptRuntime.typeof(result));
            }
            return result == null ? Boolean.FALSE : Boolean.TRUE;
        };
    }
}
