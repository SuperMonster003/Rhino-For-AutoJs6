/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * The exception of {@link Context#resumeContinuationWithError}: the captured frames are restored as
 * for {@link Context#resumeContinuation}, then the suspended call throws instead of returning, so
 * that a script {@code try} around it catches the error and {@code finally} blocks run. Exceptions
 * that the host already has as {@link RhinoException} (a WrappedException of a Java failure, say)
 * are thrown as they are; any other value is thrown as a script {@code throw} of that value at the
 * line of the suspended call.
 */
final class ContinuationError {

    private ContinuationError() {}

    /** The call of restartContinuationWithError with a top call, as NativeContinuation has it. */
    static Object topCall(NativeContinuation c, Context cx, VarScope scope, Object error) {
        Callable restart =
                (topCx, topScope, thisObj, args) ->
                        Interpreter.restartContinuationWithError(c, topCx, topScope, error);
        return ScriptRuntime.doTopCall(restart, cx, scope, null, ScriptRuntime.emptyArgs);
    }

    /** A continuation without frames has no call to throw at, so the error goes to the resumer. */
    static RuntimeException detached(Object error) {
        return exception(error);
    }

    /** The exception that {@code frame}, the restored frame of the suspended call, starts with. */
    static RuntimeException thrown(Context cx, CallFrame frame, Object error) {
        frame.savedCallOp = 0;
        cx.lastInterpreterFrame = frame;
        return exception(error);
    }

    private static RuntimeException exception(Object error) {
        if (error instanceof RhinoException) {
            return (RhinoException) error;
        }
        int[] line = new int[1];
        String source = Context.getSourcePositionFromStack(line);
        return new JavaScriptException(error, source, line[0]);
    }
}
