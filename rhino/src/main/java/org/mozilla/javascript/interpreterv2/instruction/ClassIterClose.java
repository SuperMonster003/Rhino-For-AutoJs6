/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2.instruction;

import org.mozilla.javascript.CallFrameV2;
import org.mozilla.javascript.ClassRuntime;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.interpreterv2.InstructionFormatter;

/**
 * AutoJs6 class syntax: IteratorClose of a for-of loop that ends early (the {@code
 * CLASS_ITER_CLOSE} icode). Pushes undefined, or false when the loop ends with an exception.
 */
public class ClassIterClose extends Instruction {
    private final int localIndex;
    private final boolean quiet;

    public ClassIterClose(int localIndex, boolean quiet) {
        this.localIndex = localIndex;
        this.quiet = quiet;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object enumeration = frame.getLocal(localIndex);
        frame.push(ClassRuntime.iteratorClose(enumeration, quiet, cx, frame.scope));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "slot", localIndex, "quiet", quiet);
    }
}
