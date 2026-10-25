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
 * AutoJs6 class syntax: the static private methods, static fields and static blocks of a class,
 * once its binding is initialized (the {@code CLASS_STATIC_INIT} icode). Stack: F -&gt; F.
 */
public class ClassStaticInit extends Instruction {
    public static final ClassStaticInit instance = new ClassStaticInit();

    private ClassStaticInit() {}

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object classConstructor = ClassValues.pop(frame);
        frame.push(ClassRuntime.staticInit(classConstructor, cx));
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
