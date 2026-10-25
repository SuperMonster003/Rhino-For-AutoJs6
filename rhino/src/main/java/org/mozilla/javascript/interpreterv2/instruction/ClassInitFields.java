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
 * AutoJs6 class syntax: InitializeInstanceElements at the top of a base class constructor, the
 * private methods and the fields of the class on the new object (the {@code CLASS_INIT_FIELDS}
 * icode). Pushes undefined.
 */
public class ClassInitFields extends Instruction {
    public static final ClassInitFields instance = new ClassInitFields();

    private ClassInitFields() {}

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        frame.push(ClassRuntime.initializeInstanceElements(frame.thisObj, frame.fnOrScript, cx));
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
