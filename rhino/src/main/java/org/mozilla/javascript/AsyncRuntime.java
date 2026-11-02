/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.mozilla.javascript.ClassDescriptor.Builder.value;
import static org.mozilla.javascript.ClassDescriptor.Destination.CTOR;
import static org.mozilla.javascript.ClassDescriptor.Destination.PROTO;
import static org.mozilla.javascript.Symbol.Kind.REGULAR;

import org.mozilla.javascript.ast.AstRoot;

/**
 * AutoJs6 async: the runtime of async functions (ROADMAP T7) used by the backends, and the
 * intrinsics that scripts reach only through async functions: %AsyncFunction%,
 * %AsyncGeneratorFunction%, %AsyncGeneratorPrototype% and %AsyncIteratorPrototype%. The intrinsics
 * of a realm are created by the first async function defined in it. It is public for the compiler
 * in {@code org.mozilla.javascript.optimizer}; it is not an API.
 */
public final class AsyncRuntime {

    /** {@link JSDescriptor#asyncKind()} of a function that is not async. */
    static final int NOT_ASYNC = 0;

    /** {@link JSDescriptor#asyncKind()} of an async function, method or arrow function. */
    static final int ASYNC_FUNCTION = 1;

    /** {@link JSDescriptor#asyncKind()} of an async generator function or method. */
    static final int ASYNC_GENERATOR = 2;

    static final int KIND_MASK = 3;

    /**
     * Added to the kind of a named function expression: the activation holds the name as the
     * immutable binding of the function, so that an assignment fails like a write to a read-only
     * property, whichever code does it (a nested arrow function, eval).
     */
    static final int OWN_NAME = 4;

    private static final int READONLY_DONTENUM =
            ScriptableObject.READONLY | ScriptableObject.DONTENUM;

    private static final SymbolKey ASYNC_FUNCTION_TAG =
            new SymbolKey("AsyncFunctionPrototype", REGULAR);
    private static final SymbolKey ASYNC_GENERATOR_FUNCTION_TAG =
            new SymbolKey("AsyncGeneratorFunctionPrototype", REGULAR);
    static final SymbolKey ASYNC_ITERATOR_TAG = new SymbolKey("AsyncIteratorPrototype", REGULAR);

    private static final ClassDescriptor ASYNC_FUNCTION_DESCRIPTOR =
            new ClassDescriptor.Builder(
                            ASYNC_FUNCTION_TAG,
                            "AsyncFunction",
                            1,
                            AsyncRuntime::dynamicFunction,
                            AsyncRuntime::dynamicFunction)
                    .withProp(
                            PROTO,
                            SymbolKey.TO_STRING_TAG,
                            value("AsyncFunction", READONLY_DONTENUM))
                    .build();

    private static final ClassDescriptor ASYNC_GENERATOR_FUNCTION_DESCRIPTOR =
            new ClassDescriptor.Builder(
                            ASYNC_GENERATOR_FUNCTION_TAG,
                            "AsyncGeneratorFunction",
                            1,
                            AsyncRuntime::dynamicFunction,
                            AsyncRuntime::dynamicFunction)
                    .withProp(
                            PROTO,
                            SymbolKey.TO_STRING_TAG,
                            value("AsyncGeneratorFunction", READONLY_DONTENUM))
                    .build();

    private static final ClassDescriptor ASYNC_ITERATOR_DESCRIPTOR =
            new ClassDescriptor.Builder(ASYNC_ITERATOR_TAG)
                    .withMethod(
                            CTOR,
                            SymbolKey.ASYNC_ITERATOR,
                            0,
                            (cx, f, nt, s, thisObj, args) -> thisObj)
                    .build();

    private static final JSDescriptor<JSFunction> RETURN_THIS =
            ASYNC_ITERATOR_DESCRIPTOR.findCtorDesc(SymbolKey.ASYNC_ITERATOR);

    private AsyncRuntime() {}

    /**
     * Whether {@code f} is {@code %AsyncIteratorPrototype%[@@asyncIterator]}. Built-in functions
     * get a wrapped {@code this}, but this one returns it, so that a call has to keep a primitive.
     */
    static boolean returnsThis(Callable f) {
        return f instanceof JSFunction && ((JSFunction) f).getDescriptor() == RETURN_THIS;
    }

    /** Whether a call of {@code fun} is {@code call} or {@code apply} of that function. */
    static boolean returnsThisCall(Callable fun, Object thisObj) {
        return BaseFunction.isApplyOrCall(fun)
                && returnsThis(ScriptRuntime.getCallable((Scriptable) thisObj));
    }

    /** What the body of an async function yields for an {@code await}. */
    public static final class Await extends AsyncTracker.Pending {
        Object value;

        /** A rejection is ignored (AsyncIteratorClose of a loop that ends with an exception). */
        boolean quiet;

        /** Nothing to wait for: the body continues without a promise job. */
        boolean skipped;

        Await(Object value, int lineNumber) {
            super(lineNumber);
            this.value = value;
        }
    }

    /** The value of {@code ClassOp.AWAIT}: {@code value} to yield for an await at this line. */
    public static Object await(Object value, int lineNumber) {
        if (value == ForAwait.NOTHING) {
            Await await = new Await(null, lineNumber);
            await.skipped = true;
            return await;
        }
        if (value instanceof ForAwait.Quiet) {
            Await await = new Await(((ForAwait.Quiet) value).value, lineNumber);
            await.quiet = true;
            return await;
        }
        return new Await(value, lineNumber);
    }

    /** Whether a function or script is an async function or an async generator. */
    public static boolean isAsync(ScriptOrFn<?> function) {
        return function.getDescriptor().asyncKind() != NOT_ASYNC;
    }

    /** The result of calling the async function whose first frame is {@code savedState}. */
    public static Scriptable start(Context cx, JSFunction function, Object savedState) {
        if (function.getDescriptor().asyncKind() == ASYNC_GENERATOR) {
            return NativeAsyncGenerator.create(function, savedState);
        }
        return AsyncFunctionDriver.start(cx, function, savedState);
    }

    /**
     * A direct eval in the parameters of an async function, which run in a parameter scope (see
     * {@link AsyncLowering#evalInParameters}): the var declarations of the code may not name a
     * parameter or {@code arguments} (EvalDeclarationInstantiation). A syntax error in the code is
     * left to the eval.
     */
    static void checkEvalInParameters(Context cx, VarScope scope, String source) {
        if (!(scope instanceof WithScope)
                || !(((WithScope) scope).getObject() instanceof ClassRuntime.OuterNames)
                || !(scope.getParentScope() instanceof NativeCall)) {
            return;
        }
        JSFunction function = ((NativeCall) scope.getParentScope()).function;
        CompilerEnvirons env = new CompilerEnvirons();
        env.initFromContext(cx);
        AstRoot root;
        try {
            root = new Parser(env, DefaultErrorReporter.instance).parse(source, "", 1);
        } catch (EvaluatorException e) {
            return;
        }
        boolean arguments = !function.getDescriptor().hasLexicalThis();
        if (arguments && declaresVar(root, "arguments")) {
            throw redeclaration("arguments");
        }
        for (int i = 0; i < function.getParamCount(); i++) {
            String name = function.getParamOrVarName(i);
            if (declaresVar(root, name)) {
                throw redeclaration(name);
            }
        }
    }

    private static boolean declaresVar(AstRoot root, String name) {
        var symbols = root.getSymbolTable();
        return symbols != null
                && symbols.containsKey(name)
                && !symbols.get(name).isDeclTypeLexical();
    }

    private static RhinoException redeclaration(String name) {
        return ScriptRuntime.constructError(
                "SyntaxError", ScriptRuntime.getMessageById("msg.var.redecl", name));
    }

    /** The tag of {@code Object.prototype.toString} without {@code @@toStringTag}. */
    static String builtinTag(Scriptable object) {
        String name = object.getClassName();
        return "GeneratorFunction".equals(name) ? "Function" : name;
    }

    /** GetPromiseResolve: the {@code resolve} method of a promise constructor. */
    static ScriptRuntime.LookupResult promiseResolveMethod(
            Object constructor, Context cx, VarScope scope) {
        ScriptRuntime.LookupResult resolve =
                ScriptRuntime.getPropAndThis(constructor, "resolve", cx, scope);
        if (!(resolve.getResult() instanceof Callable)) {
            throw ScriptRuntime.typeErrorById("msg.function.expected");
        }
        return resolve;
    }

    /** The value of the throw completion of {@code e}. */
    static Object thrown(RhinoException e, VarScope scope, Context cx) {
        return e instanceof JavaScriptException
                ? ((JavaScriptException) e).getValue()
                : ScriptRuntime.wrapException(e, scope, cx);
    }

    /** Runs {@code body}; a promise job runs outside of any call into a script. */
    static void inTopCall(Context cx, VarScope scope, Runnable body) {
        if (ScriptRuntime.hasTopCall(cx)) {
            body.run();
            return;
        }
        ScriptRuntime.doTopCall(
                (c, s, thisObj, args) -> {
                    body.run();
                    return Undefined.instance;
                },
                cx,
                scope,
                scope,
                ScriptRuntime.emptyArgs,
                false);
    }

    /** PromiseResolve(%Promise%, value). */
    static NativePromise promiseResolve(Context cx, VarScope scope, Object value) {
        if (value instanceof NativePromise) {
            Object constructor = ScriptableObject.getProperty((Scriptable) value, "constructor");
            if (constructor == TopLevel.getBuiltinCtor(cx, scope, TopLevel.Builtins.Promise)) {
                return (NativePromise) value;
            }
        }
        NativePromise promise = NativePromise.create(scope);
        promise.resolve(cx, scope, value);
        return promise;
    }

    /**
     * Subscribes to {@code value} without suspending a body: the steps of an async generator that
     * await in its own algorithms. {@code onRejected} also gets a PromiseResolve that fails.
     */
    static void await(
            Context cx,
            VarScope scope,
            Object value,
            NativePromise.ReactionCall onFulfilled,
            NativePromise.ReactionCall onRejected) {
        NativePromise promise;
        try {
            promise = promiseResolve(cx, scope, value);
        } catch (RhinoException e) {
            onRejected.invoke(cx, scope, thrown(e, scope, cx));
            return;
        }
        promise.react(
                cx,
                scope,
                (c, s, v) -> inTopCall(c, scope, () -> onFulfilled.invoke(c, s, v)),
                (c, s, v) -> inTopCall(c, scope, () -> onRejected.invoke(c, s, v)));
    }

    /** GetMethod: null when the property is undefined or null. */
    static Callable getMethod(Object object, Object key, Context cx, VarScope scope) {
        Object method =
                key instanceof Symbol
                        ? ScriptRuntime.getObjectElem(object, key, cx, scope)
                        : ScriptRuntime.getObjectProp(object, (String) key, cx, scope);
        if (method == null || Undefined.isUndefined(method)) {
            return null;
        }
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(object, method, key.toString());
        }
        return (Callable) method;
    }

    /** An iterator and its {@code next} method (an Iterator Record). */
    static final class IteratorRecord {
        final Object iterator;
        final Object next;
        boolean done;

        IteratorRecord(Object iterator, Object next) {
            this.iterator = iterator;
            this.next = next;
        }

        Object callNext(Context cx, VarScope scope, Object[] args) {
            if (!(next instanceof Callable)) {
                throw ScriptRuntime.notFunctionError(iterator, next, ES6Iterator.NEXT_METHOD);
            }
            return ((Callable) next).call(cx, scope, iterator, args);
        }
    }

    /** GetIteratorFromMethod. */
    static IteratorRecord iteratorFromMethod(
            Object object, Callable method, Context cx, VarScope scope) {
        Object iterator = method.call(cx, scope, object, ScriptRuntime.emptyArgs);
        return iteratorRecord(iterator, cx, scope);
    }

    /** GetIteratorDirect, with the check that the iterator is an object. */
    static IteratorRecord iteratorRecord(Object iterator, Context cx, VarScope scope) {
        if (!ScriptRuntime.isObject(iterator)) {
            throw ScriptRuntime.typeErrorById("msg.iterator.primitive");
        }
        Object next = ScriptRuntime.getObjectProp(iterator, ES6Iterator.NEXT_METHOD, cx, scope);
        return new IteratorRecord(iterator, next);
    }

    /** GetIterator(obj, async). */
    static IteratorRecord getAsyncIterator(Object object, Context cx, VarScope scope) {
        Callable method = getMethod(object, SymbolKey.ASYNC_ITERATOR, cx, scope);
        if (method != null) {
            return iteratorFromMethod(object, method, cx, scope);
        }
        Callable syncMethod = getMethod(object, SymbolKey.ITERATOR, cx, scope);
        if (syncMethod == null) {
            throw ScriptRuntime.typeErrorById(
                    "msg.not.iterable", ScriptRuntime.toString(ScriptRuntime.typeof(object)));
        }
        IteratorRecord sync = iteratorFromMethod(object, syncMethod, cx, scope);
        return AsyncFromSyncIterator.create(cx, scope, sync);
    }

    /** The [[Prototype]] of a new async function or async generator function. */
    static void setFunctionPrototype(JSFunction function, Context cx, VarScope scope) {
        boolean generator = function.getDescriptor().asyncKind() == ASYNC_GENERATOR;
        function.setPrototype(
                intrinsic(
                        cx, scope, generator ? ASYNC_GENERATOR_FUNCTION_TAG : ASYNC_FUNCTION_TAG));
    }

    /** The key in the top level scope of the object the instances of a generator inherit from. */
    static SymbolKey generatorTag(BaseFunction function) {
        return function instanceof JSFunction
                        && ((JSFunction) function).getDescriptor().asyncKind() == ASYNC_GENERATOR
                ? NativeAsyncGenerator.ASYNC_GENERATOR_TAG
                : ES6Generator.GENERATOR_TAG;
    }

    private static Scriptable dynamicFunction(
            Context cx, JSFunction f, Object nt, VarScope s, Object thisObj, Object[] args) {
        return BaseFunction.jsAsync(cx, f, nt, args);
    }

    /** The source that {@code Function.prototype.toString} gives a callable proxy, else null. */
    static String callableProxySource(Object value) {
        return value instanceof NativeProxy && value instanceof Callable
                ? "function () {\n    [native code]\n}"
                : null;
    }

    /** The source that a dynamic function starts with. */
    static String dynamicPrefix(JSFunction constructor) {
        return isAsyncConstructor(constructor) ? "async " : "";
    }

    /** Whether the constructor of a dynamic function creates async generators. */
    static boolean isGeneratorConstructor(JSFunction constructor) {
        return constructor.getDescriptor() == ASYNC_GENERATOR_FUNCTION_DESCRIPTOR.ctorDesc();
    }

    private static boolean isAsyncConstructor(JSFunction constructor) {
        return constructor.getDescriptor() == ASYNC_FUNCTION_DESCRIPTOR.ctorDesc()
                || isGeneratorConstructor(constructor);
    }

    /** The [[Prototype]] of a dynamic function; false when it is not an async function. */
    static boolean setDynamicPrototype(
            JSFunction function, JSFunction constructor, Object newTarget) {
        if (!isAsyncConstructor(constructor)) {
            return false;
        }
        function.setPrototype(BuiltinConstruction.prototypeOf(constructor, newTarget));
        return true;
    }

    /**
     * The prototype of {@code %AsyncFunction%} in {@code realm} when {@code f} is one, else null.
     */
    static Scriptable constructorPrototype(TopLevel realm, BaseFunction f) {
        if (!(f instanceof JSFunction) || !isAsyncConstructor((JSFunction) f)) {
            return null;
        }
        SymbolKey tag =
                isGeneratorConstructor((JSFunction) f)
                        ? ASYNC_GENERATOR_FUNCTION_TAG
                        : ASYNC_FUNCTION_TAG;
        return intrinsic(Context.getCurrentContext(), realm, tag);
    }

    /** An intrinsic of the realm of {@code scope}, created with the others on first use. */
    static Scriptable intrinsic(Context cx, VarScope scope, SymbolKey tag) {
        TopLevel top = ScriptableObject.getTopLevelScope(scope);
        Object value = ScriptableObject.getTopScopeValue(top, tag);
        if (value == null) {
            init(cx, top);
            value = ScriptableObject.getTopScopeValue(top, tag);
        }
        return (Scriptable) value;
    }

    private static synchronized void init(Context cx, TopLevel top) {
        if (ScriptableObject.getTopScopeValue(top, ASYNC_ITERATOR_TAG) != null) {
            return;
        }
        Scriptable objectPrototype = ScriptableObject.getObjectPrototype(top);
        boolean sealed =
                objectPrototype instanceof ScriptableObject
                        && ((ScriptableObject) objectPrototype).isSealed();

        NativeObject asyncIterator = new NativeObject();
        ScriptRuntime.setObjectProtoAndParent(asyncIterator, top);
        ASYNC_ITERATOR_DESCRIPTOR.populateGlobal(cx, top, asyncIterator, false);
        hide(top, ASYNC_ITERATOR_TAG);

        NativeObject generator = NativeAsyncGenerator.initPrototype(cx, top, asyncIterator, sealed);

        Scriptable functionConstructor = (Scriptable) ScriptableObject.getProperty(top, "Function");
        Scriptable functionPrototype = ScriptableObject.getFunctionPrototype(top);

        NativeObject asyncFunction = new NativeObject();
        defineConstructor(
                cx,
                top,
                ASYNC_FUNCTION_DESCRIPTOR,
                ASYNC_FUNCTION_TAG,
                asyncFunction,
                functionConstructor,
                functionPrototype,
                sealed);

        NativeObject asyncGeneratorFunction = new NativeObject();
        asyncGeneratorFunction.setParentScope(top);
        asyncGeneratorFunction.defineProperty(
                "prototype", generator, ScriptableObject.READONLY | ScriptableObject.DONTENUM);
        generator.defineProperty(
                "constructor",
                asyncGeneratorFunction,
                ScriptableObject.READONLY | ScriptableObject.DONTENUM);
        defineConstructor(
                cx,
                top,
                ASYNC_GENERATOR_FUNCTION_DESCRIPTOR,
                ASYNC_GENERATOR_FUNCTION_TAG,
                asyncGeneratorFunction,
                functionConstructor,
                functionPrototype,
                sealed);

        AsyncFromSyncIterator.init(cx, top, asyncIterator);
        if (sealed) {
            asyncIterator.sealObject();
            generator.sealObject();
        }
        top.associateValue(NativeAsyncGenerator.ASYNC_GENERATOR_TAG, generator);
        top.associateValue(ASYNC_FUNCTION_TAG, asyncFunction);
        top.associateValue(ASYNC_GENERATOR_FUNCTION_TAG, asyncGeneratorFunction);
        top.associateValue(ASYNC_ITERATOR_TAG, asyncIterator);
    }

    private static void defineConstructor(
            Context cx,
            TopLevel top,
            ClassDescriptor descriptor,
            SymbolKey tag,
            NativeObject prototype,
            Scriptable functionConstructor,
            Scriptable functionPrototype,
            boolean sealed) {
        descriptor.buildConstructor(
                cx,
                top,
                prototype,
                sealed,
                (c, constructor) -> {
                    constructor.setPrototype(functionConstructor);
                    prototype.setPrototype(functionPrototype);
                    prototype.setAttributes(
                            "constructor", ScriptableObject.READONLY | ScriptableObject.DONTENUM);
                });
        hide(top, tag);
    }

    /** The descriptors define their object in the scope, where scripts must not see it. */
    static void hide(TopLevel top, SymbolKey tag) {
        top.delete(tag);
        top.delete(tag.toString());
    }
}
