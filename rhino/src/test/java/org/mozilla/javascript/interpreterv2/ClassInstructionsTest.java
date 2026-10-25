/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.interpreterv2.instruction.ClassCtorCheck;
import org.mozilla.javascript.interpreterv2.instruction.ClassDefine;
import org.mozilla.javascript.interpreterv2.instruction.ClassElement;
import org.mozilla.javascript.interpreterv2.instruction.ClassInitFields;
import org.mozilla.javascript.interpreterv2.instruction.ClassIterClose;
import org.mozilla.javascript.interpreterv2.instruction.ClassIterRest;
import org.mozilla.javascript.interpreterv2.instruction.ClassParamScope;
import org.mozilla.javascript.interpreterv2.instruction.ClassPrivateName;
import org.mozilla.javascript.interpreterv2.instruction.ClassRequireObject;
import org.mozilla.javascript.interpreterv2.instruction.ClassStaticInit;
import org.mozilla.javascript.interpreterv2.instruction.ClassSuperCall;
import org.mozilla.javascript.interpreterv2.instruction.ClassSuperFunc;
import org.mozilla.javascript.interpreterv2.instruction.ClassTdz;
import org.mozilla.javascript.interpreterv2.instruction.ClassThis;
import org.mozilla.javascript.interpreterv2.instruction.Instruction;

/**
 * AutoJs6 class syntax (ROADMAP P2.3, P3.4): the Interpreter V2 instructions of the class
 * primitives. V2 has no compiler from the IR yet (ROADMAP appendix C Q11), so the instructions are
 * checked one by one: the stack change, which the V2 compiler relies on for its stack depth, and
 * the debug string; they share the runtime methods of the other two backends, which {@code
 * ClassSyntaxTest} runs.
 */
class ClassInstructionsTest {

    static Stream<Arguments> instructions() {
        return Stream.of(
                Arguments.of(
                        new ClassDefine(3, false), 1, "ClassDefine(fnIndex=3, hasHeritage=false)"),
                Arguments.of(
                        new ClassDefine(3, true), 0, "ClassDefine(fnIndex=3, hasHeritage=true)"),
                Arguments.of(new ClassElement(4, 6), -1, "ClassElement(fnIndex=4, flags=6)"),
                Arguments.of(
                        new ClassSuperCall(2, false),
                        -2,
                        "ClassSuperCall(argc=2, arrayArguments=false)"),
                Arguments.of(
                        new ClassSuperCall(0, true),
                        -1,
                        "ClassSuperCall(argc=0, arrayArguments=true)"),
                Arguments.of(ClassSuperFunc.instance, 1, "ClassSuperFunc"),
                Arguments.of(ClassThis.instance, 1, "ClassThis"),
                Arguments.of(new ClassTdz("A", false), 1, "ClassTdz(name=\"A\", parameter=false)"),
                Arguments.of(ClassCtorCheck.instance, 1, "ClassCtorCheck"),
                Arguments.of(ClassIterRest.instance, 0, "ClassIterRest"),
                Arguments.of(ClassRequireObject.instance, 0, "ClassRequireObject"),
                Arguments.of(new ClassIterClose(5, true), 1, "ClassIterClose(slot=5, quiet=true)"),
                Arguments.of(new ClassParamScope("a b"), 1, "ClassParamScope(names=\"a b\")"),
                Arguments.of(new ClassElement(-1, 0x43), -1, "ClassElement(fnIndex=-1, flags=67)"),
                Arguments.of(new ClassPrivateName("x"), 1, "ClassPrivateName(description=\"x\")"),
                Arguments.of(ClassInitFields.instance, 1, "ClassInitFields"),
                Arguments.of(ClassStaticInit.instance, 0, "ClassStaticInit"));
    }

    @ParameterizedTest
    @MethodSource("instructions")
    void stackChangeAndDebugString(Instruction instruction, int stackChange, String debug) {
        assertEquals(stackChange, instruction.stackChange());
        assertEquals(debug, instruction.toDebugString());
    }

    @Test
    void tdzThrowsReferenceError() {
        try (Context cx = Context.enter()) {
            EcmaError e =
                    assertThrows(
                            EcmaError.class, () -> new ClassTdz("x", true).interpret(cx, null));
            assertEquals("ReferenceError", e.getName());
            assertTrue(e.getErrorMessage().contains("x"), e.getErrorMessage());
        }
    }
}
