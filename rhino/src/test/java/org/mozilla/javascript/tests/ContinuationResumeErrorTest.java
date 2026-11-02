/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Context.EvaluationMethod;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.VarScope;
import org.mozilla.javascript.WrappedException;

/**
 * AutoJs6: async. {@link Context#resumeContinuationWithError}: the suspended call throws, with the
 * same frames, handlers and results as a script {@code throw} at that call.
 */
public class ContinuationResumeErrorTest {

    private TopLevel scope;

    /** Suspends: a native call that captures the script frames, as the host's await() does. */
    private static final class Suspend extends BaseFunction {
        @Override
        public Object call(
                Context cx, Object newTarget, VarScope scope, Object thisObj, Object[] args) {
            throw cx.captureContinuation();
        }
    }

    @BeforeEach
    public void setUp() {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope.getGlobalThis(), "suspend", new Suspend());
        }
    }

    private static Context enter() {
        Context cx = Context.enter();
        cx.setLanguageVersion(Context.VERSION_ES6);
        cx.setEvaluationMethod(EvaluationMethod.Interpreter);
        return cx;
    }

    private ContinuationPending start(Context cx, String source) {
        Script script = cx.compileString(source, "main.js", 1, null);
        return assertThrows(
                ContinuationPending.class, () -> cx.executeScriptWithContinuations(script, scope));
    }

    private Object error(Context cx, String message) {
        return cx.newObject(scope, "Error", new Object[] {message});
    }

    private static Object prop(Object object, String name) {
        return ScriptableObject.getProperty((Scriptable) object, name);
    }

    @Test
    public void aTryAroundTheCallCatchesTheError() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "var r; try { r = 'no ' + suspend(); } catch (e) { r = e.message; } r");
            assertEquals(
                    "failed",
                    cx.resumeContinuationWithError(
                            pending.getContinuation(), scope, error(cx, "failed")));
        }
    }

    @Test
    public void theValueIsNotAssigned() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(cx, "var r = 'kept'; try { r = suspend(); } catch (e) { } r");
            assertEquals(
                    "kept",
                    cx.resumeContinuationWithError(pending.getContinuation(), scope, "failed"));
        }
    }

    @Test
    public void withoutATryItLeavesTheResumeAtTheLineOfTheCall() {
        try (Context cx = enter()) {
            ContinuationPending pending = start(cx, "var a = 1;\nvar b = suspend();\nb + 1");
            Object error = error(cx, "failed");
            JavaScriptException thrown =
                    assertThrows(
                            JavaScriptException.class,
                            () ->
                                    cx.resumeContinuationWithError(
                                            pending.getContinuation(), scope, error));
            assertSame(error, thrown.getValue());
            assertEquals("main.js", thrown.sourceName());
            assertEquals(2, thrown.lineNumber());
            assertTrue(thrown.getScriptStackTrace().contains("main.js:2"));
        }
    }

    @Test
    public void aTryInACallerCatchesIt() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "function inner() { return suspend() + 1; }\n"
                                    + "function outer() { return inner() + 1; }\n"
                                    + "var r; try { r = outer(); } catch (e) { r = 'caught ' + e; } r");
            assertEquals(
                    "caught x",
                    cx.resumeContinuationWithError(pending.getContinuation(), scope, "x"));
        }
    }

    @Test
    public void finallyBlocksRunInEveryFrame() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "var log = [];\n"
                                    + "function inner() { try { return suspend(); } finally { log.push('inner'); } }\n"
                                    + "function outer() { try { return inner(); } finally { log.push('outer'); } }\n"
                                    + "try { outer(); } catch (e) { log.push('caught ' + e); } log.join()");
            assertEquals(
                    "inner,outer,caught x",
                    cx.resumeContinuationWithError(pending.getContinuation(), scope, "x"));
        }
    }

    @Test
    public void anyValueCanBeThrown() {
        try (Context cx = enter()) {
            String source = "var r; try { suspend(); } catch (e) { r = typeof e + ':' + e; } r";
            for (Object[] c :
                    new Object[][] {
                        {null, "object:null"},
                        {Undefined.instance, "undefined:undefined"},
                        {7, "number:7"},
                        {false, "boolean:false"},
                    }) {
                ContinuationPending pending = start(cx, source);
                assertEquals(
                        c[1],
                        cx.resumeContinuationWithError(pending.getContinuation(), scope, c[0]));
            }
        }
    }

    @Test
    public void aRhinoExceptionIsThrownAsItIs() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "var r; try { suspend(); } catch (e) { r = String(e.javaException.getMessage()); } r");
            assertEquals(
                    "from java",
                    cx.resumeContinuationWithError(
                            pending.getContinuation(),
                            scope,
                            new WrappedException(new IllegalStateException("from java"))));
        }
    }

    @Test
    public void theScriptCanSuspendAgainAfterCatching() {
        try (Context cx = enter()) {
            ContinuationPending first =
                    start(
                            cx,
                            "var log = [];\n"
                                    + "for (var i = 0; i < 3; i++) {\n"
                                    + "    try { log.push('ok ' + suspend()); } catch (e) { log.push('error ' + e); }\n"
                                    + "}\n"
                                    + "log.join()");
            ContinuationPending second =
                    assertThrows(
                            ContinuationPending.class,
                            () ->
                                    cx.resumeContinuationWithError(
                                            first.getContinuation(), scope, "a"));
            ContinuationPending third =
                    assertThrows(
                            ContinuationPending.class,
                            () -> cx.resumeContinuation(second.getContinuation(), scope, "b"));
            assertEquals(
                    "error a,ok b,error c",
                    cx.resumeContinuationWithError(third.getContinuation(), scope, "c"));
        }
    }

    @Test
    public void aCapturedContinuationStaysResumable() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "var r; try { r = 'value ' + suspend(); } catch (e) { r = 'error ' + e; } r");
            Object continuation = pending.getContinuation();
            assertEquals("error x", cx.resumeContinuationWithError(continuation, scope, "x"));
            assertEquals("value y", cx.resumeContinuation(continuation, scope, "y"));
            assertEquals("error z", cx.resumeContinuationWithError(continuation, scope, "z"));
        }
    }

    @Test
    public void itWorksInsideARunningTopCall() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(cx, "var r; try { suspend(); } catch (e) { r = 'caught ' + e; } r");
            Object continuation = pending.getContinuation();
            BaseFunction resume =
                    new BaseFunction() {
                        @Override
                        public Object call(
                                Context c,
                                Object newTarget,
                                VarScope s,
                                Object thisObj,
                                Object[] args) {
                            return c.resumeContinuationWithError(continuation, s, args[0]);
                        }
                    };
            ScriptableObject.putProperty(scope.getGlobalThis(), "resume", resume);
            assertEquals(
                    "caught x and back",
                    cx.evaluateString(scope, "resume('x') + ' and back'", "caller.js", 1, null));
        }
    }

    @Test
    public void aSuspendedNewThrowsAsWell() {
        try (Context cx = enter()) {
            ContinuationPending pending =
                    start(
                            cx,
                            "function F() { suspend(); }\n"
                                    + "var r; try { r = new F(); } catch (e) { r = 'caught ' + e; } r");
            assertEquals(
                    "caught x",
                    cx.resumeContinuationWithError(pending.getContinuation(), scope, "x"));
        }
    }
}
