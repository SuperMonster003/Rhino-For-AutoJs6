/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.HashMap;
import java.util.Map;

/**
 * This is an implementation of the standard "Symbol" type that implements all of its weird
 * properties. One of them is that some objects can have an "internal data slot" that makes them a
 * Symbol and others cannot.
 */
public class NativeSymbol extends ScriptableObject implements Symbol {
    private static final long serialVersionUID = -589539749749830003L;

    public static final String CLASS_NAME = "Symbol";
    public static final String TYPE_NAME = "symbol";

    private static final Object GLOBAL_TABLE_KEY = new Object();

    private final SymbolKey key;
    private final NativeSymbol symbolData;

    public static void init(Context cx, Scriptable scope, boolean sealed) {
        LambdaConstructor ctor =
                new LambdaConstructor(
                        scope,
                        CLASS_NAME,
                        0,
                        LambdaConstructor.CONSTRUCTOR_FUNCTION,
                        NativeSymbol::js_constructor);

        ctor.setPrototypePropertyAttributes(DONTENUM | READONLY | PERMANENT);

        ctor.defineConstructorMethod(
                scope,
                "for",
                1,
                (lcx, lscope, thisObj, args) -> NativeSymbol.js_for(lcx, lscope, args, ctor),
                DONTENUM,
                DONTENUM | READONLY);
        ctor.defineConstructorMethod(
                scope, "keyFor", 1, NativeSymbol::js_keyFor, DONTENUM, DONTENUM | READONLY);

        ctor.definePrototypeMethod(
                scope, "toString", 0, NativeSymbol::js_toString, DONTENUM, DONTENUM | READONLY);
        ctor.definePrototypeMethod(
                scope, "valueOf", 0, NativeSymbol::js_valueOf, DONTENUM, DONTENUM | READONLY);
        ctor.definePrototypeMethod(
                scope,
                SymbolKey.TO_PRIMITIVE,
                1,
                NativeSymbol::js_valueOf,
                DONTENUM | READONLY,
                DONTENUM | READONLY);
        ctor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM | READONLY);
        ctor.definePrototypeProperty(
                cx, "description", NativeSymbol::js_description, DONTENUM | READONLY);

        ScriptableObject.defineProperty(scope, CLASS_NAME, ctor, DONTENUM);

        // Create all the predefined symbols and bind them to the scope.
        createStandardSymbol(cx, scope, ctor, "iterator", SymbolKey.ITERATOR);
        createStandardSymbol(cx, scope, ctor, "species", SymbolKey.SPECIES);
        createStandardSymbol(cx, scope, ctor, "toStringTag", SymbolKey.TO_STRING_TAG);
        createStandardSymbol(cx, scope, ctor, "hasInstance", SymbolKey.HAS_INSTANCE);
        createStandardSymbol(cx, scope, ctor, "isConcatSpreadable", SymbolKey.IS_CONCAT_SPREADABLE);
        createStandardSymbol(cx, scope, ctor, "isRegExp", SymbolKey.IS_REGEXP);
        createStandardSymbol(cx, scope, ctor, "toPrimitive", SymbolKey.TO_PRIMITIVE);
        createStandardSymbol(cx, scope, ctor, "match", SymbolKey.MATCH);
        createStandardSymbol(cx, scope, ctor, "matchAll", SymbolKey.MATCH_ALL);
        createStandardSymbol(cx, scope, ctor, "replace", SymbolKey.REPLACE);
        createStandardSymbol(cx, scope, ctor, "search", SymbolKey.SEARCH);
        createStandardSymbol(cx, scope, ctor, "split", SymbolKey.SPLIT);
        createStandardSymbol(cx, scope, ctor, "unscopables", SymbolKey.UNSCOPABLES);

        if (sealed) {
            // Can't seal until we have created all the stuff above!
            ctor.sealObject();
        }
    }

    NativeSymbol(SymbolKey key) {
        this.key = key;
        this.symbolData = this;
    }

    public NativeSymbol(NativeSymbol s) {
        this.key = s.key;
        this.symbolData = s.symbolData;
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    /**
     * Create a symbol directly. We use this internally to construct new symbols as if the
     * constructor was called directly.
     */
    private static NativeSymbol constructSymbol(
            Context cx, Scriptable scope, LambdaConstructor ctor, SymbolKey key) {
        return (NativeSymbol) ctor.call(cx, scope, null, new Object[] {Undefined.instance, key});
    }

    private static NativeSymbol constructSymbol(
            Context cx, Scriptable scope, LambdaConstructor ctor, String name) {
        return (NativeSymbol) ctor.call(cx, scope, null, new Object[] {name});
    }

    private static void createStandardSymbol(
            Context cx, Scriptable scope, LambdaConstructor ctor, String name, SymbolKey key) {
        NativeSymbol sym = constructSymbol(cx, scope, ctor, key);
        ctor.defineProperty(name, sym, DONTENUM | READONLY | PERMANENT);
    }

    private static NativeSymbol getSelf(Scriptable thisObj) {
        return LambdaConstructor.convertThisObject(thisObj, NativeSymbol.class);
    }

    private static NativeSymbol js_constructor(Context cx, Scriptable scope, Object[] args) {
        String desc = null;
        if (args.length > 0 && !Undefined.isUndefined(args[0])) {
            desc = ScriptRuntime.toString(args[0]);
        }

        if (args.length > 1) {
            return new NativeSymbol((SymbolKey) args[1]);
        }

        return new NativeSymbol(new SymbolKey(desc));
    }

    private static String js_toString(
            Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        return getSelf(thisObj).toString();
    }

    private static Object js_valueOf(
            Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        return getSelf(thisObj).symbolData;
    }

    private static Object js_description(Scriptable thisObj) {
        return getSelf(thisObj).getKey().getDescription();
    }

    private static Object js_for(
            Context cx, Scriptable scope, Object[] args, LambdaConstructor constructor) {
        String name =
                (args.length > 0
                        ? ScriptRuntime.toString(args[0])
                        : ScriptRuntime.toString(Undefined.instance));

        Map<String, NativeSymbol> table = getGlobalMap(scope);
        return table.computeIfAbsent(name, (k) -> constructSymbol(cx, scope, constructor, name));
    }

    @SuppressWarnings("ReferenceEquality")
    private static Object js_keyFor(
            Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        Object s = (args.length > 0 ? args[0] : Undefined.instance);
        if (!(s instanceof NativeSymbol)) {
            throw ScriptRuntime.throwCustomError(cx, scope, "TypeError", "Not a Symbol");
        }
        NativeSymbol sym = (NativeSymbol) s;

        Map<String, NativeSymbol> table = getGlobalMap(scope);
        for (Map.Entry<String, NativeSymbol> e : table.entrySet()) {
            if (e.getValue().key == sym.key) {
                return e.getKey();
            }
        }
        return Undefined.instance;
    }

    @Override
    public String toString() {
        return key.toString();
    }

    // Symbol objects have a special property that one cannot add properties.

    private static boolean isStrictMode() {
        final Context cx = Context.getCurrentContext();
        return (cx != null) && cx.isStrictMode();
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (!isSymbol()) {
            super.put(name, start, value);
        } else if (isStrictMode()) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict");
        }
    }

    @Override
    public void put(int index, Scriptable start, Object value) {
        if (!isSymbol()) {
            super.put(index, start, value);
        } else if (isStrictMode()) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict");
        }
    }

    @Override
    public void put(Symbol key, Scriptable start, Object value) {
        if (!isSymbol()) {
            super.put(key, start, value);
        } else if (isStrictMode()) {
            throw ScriptRuntime.typeErrorById("msg.no.assign.symbol.strict");
        }
    }

    /**
     * Object() on a Symbol constructs an object which is NOT a symbol, but which has an "internal
     * data slot" that is. Furthermore, such an object has the Symbol prototype so this particular
     * object is still used. Account for that here: an "Object" that was created from a Symbol has a
     * different value of the slot.
     */
    @SuppressWarnings("ReferenceEquality")
    public boolean isSymbol() {
        return (symbolData == this);
    }

    @Override
    public String getTypeOf() {
        return (isSymbol() ? TYPE_NAME : super.getTypeOf());
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public boolean equals(Object x) {
        return key.equals(x);
    }

    SymbolKey getKey() {
        return key;
    }

    /**
     * Return the Map that stores global symbols for the 'for' and 'keyFor' operations. It must work
     * across "realms" in the same top-level Rhino scope, so we store it there as an associated
     * property.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, NativeSymbol> getGlobalMap(Scriptable scope) {
        ScriptableObject top = (ScriptableObject) getTopLevelScope(scope);
        Map<String, NativeSymbol> map =
                (Map<String, NativeSymbol>) top.getAssociatedValue(GLOBAL_TABLE_KEY);
        if (map == null) {
            map = new HashMap<>();
            top.associateValue(GLOBAL_TABLE_KEY, map);
        }
        return map;
    }
}