package org.mozilla.javascript.tests;

import org.junit.Test;

public class NullishCoalescingOpTest {
    @Test
    public void testNullishCoalescingOperatorRequiresES6() {
        Utils.assertEvaluatorException_1_8("syntax error (test#1)", "true ?? false");
    }

    @Test
    public void testNullishCoalescingBasic() {
        Utils.assertWithAllModes_ES6("val", "'val' ?? 'default string'");
        Utils.assertWithAllModes_ES6("default string", "null ?? 'default string'");
        Utils.assertWithAllModes_ES6("default string", "undefined ?? 'default string'");
    }

    @Test
    public void testNullishCoalescingShortCircuit() {
        String script = "0 || 0 ?? true";
        Utils.assertEvaluatorExceptionES6("Syntax Error: Unexpected token. (test#1)", script);

        String script2 = "0 && 0 ?? true";
        Utils.assertEvaluatorExceptionES6("Syntax Error: Unexpected token. (test#1)", script2);

        String script3 = "0 ?? 0 && true;";
        Utils.assertEvaluatorExceptionES6("Syntax Error: Unexpected token. (test#1)", script3);

        String script4 = "0 ?? 0 || true;";
        Utils.assertEvaluatorExceptionES6("Syntax Error: Unexpected token. (test#1)", script4);
    }

    @Test
    public void testNullishCoalescingPrecedence() {
        Utils.assertWithAllModes_ES6("yes", "3 == 3 ? 'yes' ?? 'default string' : 'no'");
    }

    @Test
    public void testNullishCoalescingEvalOnce() {
        String script =
                "var runs = 0; \n"
                        + "function f() { runs++; return 3; } \n"
                        + "var eval1 = f() ?? 42; \n"
                        + "runs";
        Utils.assertWithAllModes_ES6(1, script);
    }

    @Test
    public void testNullishCoalescingDoesNotEvaluateRightHandSideIfNotNecessary() {
        String script =
                "var runs = 0; \n"
                        + "function f() { runs++; return 3; } \n"
                        + "var eval1 = 42 ?? f(); \n"
                        + "runs";
        Utils.assertWithAllModes_ES6(0, script);
    }

    @Test
    public void testNullishCoalescingDoesNotLeakVariables() {
        String script = "$0 = false; true ?? true; $0";
        Utils.assertWithAllModes_ES6(false, script);
    }

    @Test
    public void testNullishAssignmentRequiresES6() {
        Utils.assertEvaluatorException_1_8("syntax error (test#1)", "a = true; a ??= false");
    }

    @Test
    public void testNullishAssignment() {
        Utils.assertWithAllModes_ES6(true, "a = true; a ??= false; a");
        Utils.assertWithAllModes_ES6(false, "a = undefined; a ??= false; a");
        Utils.assertWithAllModes_ES6(false, "a = null; a ??= false; a");
    }
}