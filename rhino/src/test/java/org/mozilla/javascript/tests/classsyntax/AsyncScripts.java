/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.testutils.Utils;

/** AutoJs6 async: runs a script and then its microtasks, in both modes. */
final class AsyncScripts {

    private AsyncScripts() {}

    /** A script writes to the array {@code log}, which is compared once the microtasks ran. */
    static void assertLog(String expected, String script) {
        Utils.runWithAllModes(
                cx -> {
                    assertEquals(expected, runLog(cx, script));
                    return null;
                });
    }

    static String runLog(Context cx, String script) {
        cx.setLanguageVersion(Context.VERSION_ES6);
        TopLevel scope = cx.initStandardObjects();
        cx.evaluateString(scope, "var log = []; " + script, "test.js", 1, null);
        cx.processMicrotasks();
        return ScriptRuntime.toString(cx.evaluateString(scope, "log.join('|')", "log", 1, null));
    }
}
