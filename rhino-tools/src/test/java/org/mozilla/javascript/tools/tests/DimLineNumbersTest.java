/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tools.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.tools.debugger.Dim;
import org.mozilla.javascript.tools.debugger.GuiCallback;

/**
 * AutoJs6 (appendix C Q60): {@link Dim.SourceInfo} asks for the lines of a script when it is
 * compiled, so a script with for-of or a class failed to compile in the debugger.
 */
public class DimLineNumbersTest {

    private static final class Callback implements GuiCallback {
        Dim.SourceInfo source;

        @Override
        public void updateSourceText(Dim.SourceInfo sourceInfo) {
            source = sourceInfo;
        }

        @Override
        public void enterInterrupt(Dim.StackFrame frame, String title, String message) {}

        @Override
        public boolean isGuiEventThread() {
            return false;
        }

        @Override
        public void dispatchNextGuiEvent() {}
    }

    @Test
    public void compilesForOfAndClasses() {
        ContextFactory factory =
                new ContextFactory() {
                    @Override
                    protected void onContextCreated(Context cx) {
                        cx.setLanguageVersion(Context.VERSION_ES6);
                        super.onContextCreated(cx);
                    }
                };
        Dim dim = new Dim();
        Callback callback = new Callback();
        dim.setGuiCallback(callback);
        dim.attachTo(factory);
        try {
            dim.compileScript(
                    "test.js",
                    """
                    class A {}
                    class B extends A {
                        m() {
                            return 1;
                        }
                    }
                    for (var x of [new B()]) {
                        x.m();
                    }
                    var [y] = [1];
                    async function f() {
                        for await (var v of []) {
                            v;
                        }
                    }
                    """);
            Dim.SourceInfo info = dim.sourceInfo("test.js");
            assertNotNull(info);
            assertSame(info, callback.source);
            for (int line : new int[] {4, 7, 8, 10, 13}) {
                assertTrue(info.breakableLine(line), "line " + line);
            }
        } finally {
            dim.detach();
        }
    }
}
