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
 * AutoJs6 class syntax: pushes the super constructor of the derived constructor, before the
 * arguments of {@code super(...)} are evaluated (the {@code CLASS_SUPER_FUNC} icode).
 */
public class ClassSuperFunc extends Instruction {
    public static final ClassSuperFunc instance = new ClassSuperFunc();

    private ClassSuperFunc() {}

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        frame.push(ClassRuntime.superConstructor(frame.thisObj));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this);
    }
}
