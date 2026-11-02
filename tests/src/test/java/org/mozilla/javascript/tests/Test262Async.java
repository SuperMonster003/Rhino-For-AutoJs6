/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.VarScope;

/**
 * AutoJs6 async: the protocol of the test262 tests with the {@code async} flag (ROADMAP P9.4). Such
 * a test ends by printing a line through {@code $DONE} of {@code harness/doneprintHandle.js}, once
 * the microtasks ran, so {@code print} is the one host function it needs.
 */
final class Test262Async {
    /** The harness file of a test with the {@code async} flag. */
    static final String HARNESS = "doneprintHandle.js";

    private static final String COMPLETE = "Test262:AsyncTestComplete";
    private static final String FAILURE = "Test262:AsyncTestFailure:";

    private String done;

    private Test262Async() {}

    /** Defines {@code print} in {@code scope}. */
    static Test262Async install(VarScope scope) {
        Test262Async async = new Test262Async();
        ScriptableObject.putProperty(
                scope,
                "print",
                new LambdaFunction(
                        scope,
                        "print",
                        1,
                        (cx, s, thisObj, args) -> {
                            if (async.done == null && args.length > 0) {
                                async.done = ScriptRuntime.toString(args[0]);
                            }
                            return Undefined.instance;
                        }));
        return async;
    }

    /** Runs the microtasks; returns why the test failed, or {@code null} when it passed. */
    String failure(Context cx) {
        cx.processMicrotasks();
        if (done == null) {
            return "$DONE was not called";
        }
        if (done.startsWith(FAILURE)) {
            return done.substring(FAILURE.length());
        }
        return COMPLETE.equals(done) ? null : "unexpected output: " + done;
    }
}
