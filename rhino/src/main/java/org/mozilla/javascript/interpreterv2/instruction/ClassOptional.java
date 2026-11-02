/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2.instruction;

import org.mozilla.javascript.CallFrameV2;
import org.mozilla.javascript.ClassRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.interpreterv2.InstructionFormatter;

/**
 * AutoJs6: the value before a {@code ?.} (the {@code CLASS_OPTIONAL} and {@code
 * CLASS_OPTIONAL_CALLEE} icodes, see {@code ClassOp.OPTIONAL_CHAIN}). The value stays on the stack
 * for the test and goes into the local; of a callee, the local gets the lookup result and the stack
 * its function.
 */
public class ClassOptional extends Instruction {
    private final int localIndex;
    private final boolean callee;

    public ClassOptional(int localIndex, boolean callee) {
        this.localIndex = localIndex;
        this.callee = callee;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object value = ClassValues.pop(frame);
        frame.setLocal(localIndex, value);
        frame.push(
                callee ? ClassRuntime.optionalCallee((ScriptRuntime.LookupResult) value) : value);
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 0;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "slot", localIndex, "callee", callee);
    }
}
