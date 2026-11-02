/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2.instruction;

import org.mozilla.javascript.CallFrameV2;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ForAwait;
import org.mozilla.javascript.interpreterv2.InstructionFormatter;

/**
 * AutoJs6 async: a step of a {@code for await} loop (the {@code CLASS_FOR_AWAIT} icode). OPEN keeps
 * the record of the loop in the local as well. A step with a child replaces it on the stack, the
 * others push their result.
 */
public class ClassForAwait extends Instruction {
    private final int localIndex;
    private final int step;

    public ClassForAwait(int localIndex, int step) {
        this.localIndex = localIndex;
        this.step = step;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object value = ForAwait.hasValue(step) ? ClassValues.pop(frame) : null;
        Object record = step == ForAwait.OPEN ? null : frame.getLocal(localIndex);
        Object result = ForAwait.step(value, record, step, cx, frame.scope);
        if (step == ForAwait.OPEN) {
            frame.setLocal(localIndex, result);
        }
        frame.push(result);
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return ForAwait.hasValue(step) ? 0 : 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "slot", localIndex, "step", step);
    }
}
