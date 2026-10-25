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
 * AutoJs6 class syntax: RequireObjectCoercible of the value bound to an empty object pattern (the
 * {@code CLASS_REQUIRE_OBJECT} icode). Leaves the value on the stack.
 */
public class ClassRequireObject extends Instruction {
    public static final ClassRequireObject instance = new ClassRequireObject();

    private ClassRequireObject() {}

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        frame.push(ClassRuntime.requireObjectCoercible(ClassValues.pop(frame)));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 0;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this);
    }
}
