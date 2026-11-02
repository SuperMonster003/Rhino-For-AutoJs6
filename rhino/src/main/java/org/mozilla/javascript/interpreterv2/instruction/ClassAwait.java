/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2.instruction;

import org.mozilla.javascript.AsyncRuntime;
import org.mozilla.javascript.CallFrameV2;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.interpreterv2.InstructionFormatter;

/**
 * AutoJs6 async: the value that an {@code await} yields (the {@code CLASS_AWAIT} icode). Replaces
 * the operand on the stack; the yield itself is the generator instruction that follows.
 */
public class ClassAwait extends Instruction {
    private final int lineNumber;

    public ClassAwait(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        frame.push(AsyncRuntime.await(ClassValues.pop(frame), lineNumber));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 0;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "line", lineNumber);
    }
}
