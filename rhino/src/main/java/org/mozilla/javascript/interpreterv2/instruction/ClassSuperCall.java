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
 * AutoJs6 class syntax: {@code super(...)} (the {@code CLASS_SUPER_CALL} icode). Stack: super
 * constructor, arguments (or one array holding them) -&gt; this.
 */
public class ClassSuperCall extends Instruction {
    private final int argc;
    private final boolean arrayArguments;

    public ClassSuperCall(int argc, boolean arrayArguments) {
        this.argc = argc;
        this.arrayArguments = arrayArguments;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object result;
        if (arrayArguments) {
            Object array = frame.pop();
            Object superConstructor = frame.pop();
            result =
                    ClassRuntime.superCallArray(
                            superConstructor,
                            array,
                            cx,
                            frame.scope,
                            frame.thisObj,
                            frame.newTarget);
        } else {
            Object[] args = new Object[argc];
            for (int i = argc - 1; i >= 0; i--) {
                args[i] = ClassValues.pop(frame);
            }
            Object superConstructor = frame.pop();
            result =
                    ClassRuntime.superCall(
                            superConstructor,
                            args,
                            cx,
                            frame.scope,
                            frame.thisObj,
                            frame.newTarget);
        }
        frame.push(result);
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return -(arrayArguments ? 1 : argc);
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(
                this, "argc", argc, "arrayArguments", arrayArguments);
    }
}
