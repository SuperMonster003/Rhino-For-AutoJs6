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
 * AutoJs6 class syntax: defines a method, accessor, generator method, field or static block of a
 * class (the {@code CLASS_ELEMENT} icode); a field without an initializer has no function, {@code
 * fnIndex} -1. Stack: F, key -&gt; F.
 */
public class ClassElement extends Instruction {
    private final int fnIndex;
    private final int flags;

    public ClassElement(int fnIndex, int flags) {
        this.fnIndex = fnIndex;
        this.flags = flags;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object key = ClassValues.pop(frame);
        var method = fnIndex < 0 ? null : frame.fnOrScript.getDescriptor().getFunction(fnIndex);
        ClassRuntime.defineElement(frame.peek(), key, method, flags, cx, frame.scope);
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return -1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "fnIndex", fnIndex, "flags", flags);
    }
}
