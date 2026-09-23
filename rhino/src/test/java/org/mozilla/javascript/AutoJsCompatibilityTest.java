package org.mozilla.javascript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class AutoJsCompatibilityTest {
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
