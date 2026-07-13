/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*- */

package org.mozilla.javascript;

import java.io.Serial;
import java.io.Serializable;

/**
 * Compatibility wrapper for the legacy public NativeWith type.
 *
 * <p>The runtime no longer depends on this class for with-statement execution, but embedders may
 * still use it to recognize values that wrap another scriptable object.
 */
public class NativeWith implements Scriptable, SymbolScriptable, IdFunctionCall, Serializable {
    @Serial private static final long serialVersionUID = 1L;

    static void init(VarScope scope, boolean sealed) {
        NativeWith obj = new NativeWith();

        obj.setParentScope(scope);
        obj.setPrototype(ScriptableObject.getObjectPrototype(scope));

        IdFunctionObject ctor = new IdFunctionObject(obj, FTAG, Id_constructor, "With", 0, scope);
        ctor.markAsConstructor(obj);
        if (sealed) {
            ctor.sealObject();
        }
        ctor.exportAsScopeProperty();
    }

    private NativeWith() {}

    protected NativeWith(VarScope parent, Scriptable prototype) {
        this.parent = parent;
        this.prototype = prototype;
    }

    @Override
    public String getClassName() {
        return "With";
    }

    @Override
    public boolean has(String id, Scriptable start) {
        return prototype.has(id, prototype);
    }

    @Override
    public boolean has(Symbol key, Scriptable start) {
        if (prototype instanceof SymbolScriptable) {
            return ((SymbolScriptable) prototype).has(key, prototype);
        }
        return false;
    }

    @Override
    public boolean has(int index, Scriptable start) {
        return prototype.has(index, prototype);
    }

    @Override
    public Object get(String id, Scriptable start) {
        if (start == this) {
            start = prototype;
        }
        return prototype.get(id, start);
    }

    @Override
    public Object get(Symbol key, Scriptable start) {
        if (start == this) {
            start = prototype;
        }
        if (prototype instanceof SymbolScriptable) {
            return ((SymbolScriptable) prototype).get(key, start);
        }
        return Scriptable.NOT_FOUND;
    }

    @Override
    public Object get(int index, Scriptable start) {
        if (start == this) {
            start = prototype;
        }
        return prototype.get(index, start);
    }

    @Override
    public void put(String id, Scriptable start, Object value) {
        if (start == this) {
            start = prototype;
        }
        prototype.put(id, start, value);
    }

    @Override
    public void put(Symbol symbol, Scriptable start, Object value) {
        if (start == this) {
            start = prototype;
        }
        if (prototype instanceof SymbolScriptable) {
            ((SymbolScriptable) prototype).put(symbol, start, value);
        }
    }

    @Override
    public void put(int index, Scriptable start, Object value) {
        if (start == this) {
            start = prototype;
        }
        prototype.put(index, start, value);
    }

    @Override
    public void delete(String id) {
        prototype.delete(id);
    }

    @Override
    public void delete(Symbol key) {
        if (prototype instanceof SymbolScriptable) {
            ((SymbolScriptable) prototype).delete(key);
        }
    }

    @Override
    public void delete(int index) {
        prototype.delete(index);
    }

    @Override
    public Scriptable getPrototype() {
        return prototype;
    }

    @Override
    public void setPrototype(Scriptable prototype) {
        this.prototype = prototype;
    }

    @Override
    public VarScope getParentScope() {
        return parent;
    }

    @Override
    public void setParentScope(VarScope parent) {
        this.parent = parent;
    }

    @Override
    public Object[] getIds() {
        return prototype.getIds();
    }

    @Override
    public Object getDefaultValue(Class<?> typeHint) {
        return prototype.getDefaultValue(typeHint);
    }

    @Override
    public boolean hasInstance(Scriptable value) {
        return prototype.hasInstance(value);
    }

    protected Object updateDotQuery(boolean value) {
        throw new IllegalStateException();
    }

    @Override
    public Object execIdCall(
            IdFunctionObject f, Context cx, VarScope scope, Scriptable thisObj, Object[] args) {
        if (f.hasTag(FTAG) && f.methodId() == Id_constructor) {
            throw Context.reportRuntimeErrorById("msg.cant.call.indirect", "With");
        }
        throw f.unknown();
    }

    static boolean isWithFunction(Object functionObj) {
        if (functionObj instanceof IdFunctionObject) {
            IdFunctionObject f = (IdFunctionObject) functionObj;
            return f.hasTag(FTAG) && f.methodId() == Id_constructor;
        }
        return false;
    }

    static Object newWithSpecial(Context cx, VarScope scope, Object[] args) {
        ScriptRuntime.checkDeprecated(cx, "With");
        VarScope top = ScriptableObject.getTopLevelScope(scope);
        NativeWith thisObj = new NativeWith();
        thisObj.setPrototype(
                args.length == 0
                        ? ScriptableObject.getObjectPrototype(top)
                        : ScriptRuntime.toObject(cx, top, args[0]));
        thisObj.setParentScope(top);
        return thisObj;
    }

    private static final Object FTAG = "With";
    private static final int Id_constructor = 1;

    protected Scriptable prototype;
    protected VarScope parent;
}
