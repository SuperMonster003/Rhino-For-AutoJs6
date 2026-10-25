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
 * AutoJs6 class syntax: creates the Private Name a class evaluation declares, the value of its
 * hidden binding in the class scope (the {@code CLASS_PRIVATE_NAME} icode). Pushes the name.
 */
public class ClassPrivateName extends Instruction {
    private final String description;

    public ClassPrivateName(String description) {
        this.description = description;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        frame.push(ClassRuntime.newPrivateName(description));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "description", description);
    }
}
