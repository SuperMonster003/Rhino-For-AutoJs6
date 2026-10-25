/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import org.mozilla.javascript.Context;

/**
 * AutoJs6 class syntax (ROADMAP P6.2, T8, appendix C Q11): the opt-in Interpreter V2 mode of {@code
 * Test262SuiteTest}. {@code -Drhino.test262.interpreterV2=true} asks for it (Gradle passes every
 * {@code rhino.*} property to the tests); it is not part of the default matrix, and it runs only
 * when a context can compile a script with Interpreter V2. Upstream has no IR to V2 compiler yet,
 * so for now the mode reports why it is unavailable and runs no cases; once the compiler lands the
 * same switch runs the whole suite, including the class directories, with V2.
 */
final class InterpreterV2Mode {

    static final String PROPERTY = "rhino.test262.interpreterV2";

    /** Whether the V2 mode runs: requested with {@link #PROPERTY} and available. */
    static final boolean RUN = Boolean.getBoolean(PROPERTY) && available();

    private InterpreterV2Mode() {}

    /** The number of test modes, the V2 mode counting only when it runs. */
    static int modeCount() {
        return Test262SuiteTest.TestMode.values().length - (RUN ? 0 : 1);
    }

    @SuppressWarnings("deprecation")
    static void setup(Context cx) {
        cx.setOptimizationLevel(-2);
    }

    /** Returns why Interpreter V2 cannot run a script, or {@code null} when it can. */
    static String unavailable() {
        try (Context cx = Context.enter()) {
            setup(cx);
            cx.compileString("1", "interpreterV2", 1, null);
            return null;
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    private static boolean available() {
        String reason = unavailable();
        if (reason != null) {
            System.out.println(
                    "Interpreter V2 mode (-D"
                            + PROPERTY
                            + ") is not available, 0 cases run: "
                            + reason);
        }
        return reason == null;
    }
}
