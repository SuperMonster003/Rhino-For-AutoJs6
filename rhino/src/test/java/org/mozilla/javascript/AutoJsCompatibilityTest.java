package org.mozilla.javascript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AutoJsCompatibilityTest {
    @Test
    void reflectiveLegacyScopeLookupCanRegisterAndCallJavaClasses() throws Exception {
        var lookups =
                Arrays.stream(ScriptRuntime.class.getDeclaredMethods())
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getReturnType() == Scriptable.class)
                        .filter(
                                method ->
                                        Arrays.equals(
                                                method.getParameterTypes(),
                                                new Class<?>[] {Context.class}))
                        .toList();
        var topLevel = ScriptableObject.class.getMethod("getTopLevelScope", Scriptable.class);
        var wrap = NativeJavaClass.class.getConstructor(Scriptable.class, Class.class);
        var put = Scriptable.class.getMethod("put", String.class, Scriptable.class, Object.class);
        try (Context cx = Context.enter()) {
            TopLevel scope = cx.initStandardObjects();
            scope.getGlobalThis().setParentScope(scope);
            Object registered =
                    ScriptRuntime.doTopCall(
                            (context, callScope, thisObj, args) -> {
                                try {
                                    Object active = null;
                                    for (var lookup : lookups) {
                                        try {
                                            active = lookup.invoke(null, context);
                                        } catch (ReflectiveOperationException ignored) {
                                            continue;
                                        }
                                        if (active != null) break;
                                    }
                                    assertNotNull(active);
                                    Object global = topLevel.invoke(null, active);
                                    assertSame(scope.getGlobalThis(), active);
                                    assertSame(active, global);
                                    Object type = wrap.newInstance(global, StringBuilder.class);
                                    put.invoke(global, "RegisteredClass", global, type);
                                    return context.evaluateString(
                                            callScope,
                                            "new RegisteredClass('Kotlin').append('-OK').toString()",
                                            "legacy-class-registration",
                                            1,
                                            null);
                                } catch (ReflectiveOperationException failure) {
                                    throw new AssertionError(failure);
                                }
                            },
                            cx,
                            scope,
                            scope.getGlobalThis(),
                            Context.emptyArgs,
                            false);
            assertEquals("Kotlin-OK", Context.toString(registered));
        }
    }

    @Test
    void legacyFunctionConstructionRetainsItsAssignedScopeAndCallsNewSignature() {
        try (Context cx = Context.enter()) {
            TopLevel scope = cx.initStandardObjects();
            scope.getGlobalThis().setParentScope(scope);
            BaseFunction function =
                    new BaseFunction() {
                        @Override
                        public Object call(
                                Context context,
                                Object newTarget,
                                VarScope callScope,
                                Object thisObj,
                                Object[] args) {
                            assertSame(Undefined.instance, newTarget);
                            assertSame(scope, callScope);
                            return thisObj;
                        }
                    };
            function.setParentScope(scope);
            assertSame(scope, function.getDeclarationScope());
            assertEquals(42, function.call(cx, scope.getGlobalThis(), 42, Context.emptyArgs));
        }
    }

    @Test
    void legacyEvalOverloadsResolveBothScopeRepresentations() {
        try (Context cx = Context.enter()) {
            TopLevel scope = cx.initStandardObjects();
            scope.getGlobalThis().setParentScope(scope);
            scope.put("value", scope, 41);
            Object[] args = {"value + 1"};
            assertEquals(
                    42.0,
                    ScriptRuntime.evalSpecial(
                            cx, scope, scope.getGlobalThis(), args, "legacy-eval", 1));
            assertEquals(
                    42.0,
                    ScriptRuntime.evalSpecial(
                            cx,
                            scope.getGlobalThis(),
                            scope.getGlobalThis(),
                            args,
                            "legacy-eval",
                            1));
        }
    }

    @Test
    void nestedContextReleaseClearsVmBridgeOnlyAfterFinalExit() {
        Context outer = Context.enter();
        try {
            try (Context inner = Context.enter()) {
                assertSame(outer, inner);
                inner.initStandardObjects();
            }
            assertSame(outer, Context.getCurrentContext());
            assertSame(
                    outer,
                    VMBridge.instance.getContext(VMBridge.instance.getThreadContextHelper()));
        } finally {
            Context.exit();
        }
        assertNull(Context.getCurrentContext());
        assertNull(VMBridge.instance.getContext(VMBridge.instance.getThreadContextHelper()));
    }
}
