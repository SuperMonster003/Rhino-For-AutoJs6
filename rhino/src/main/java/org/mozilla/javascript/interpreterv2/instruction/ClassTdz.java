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
 * AutoJs6 class syntax: a reference to the class binding while the class is defined, or to a
 * parameter from the default value of an earlier parameter, which always throws a ReferenceError
 * (the {@code CLASS_TDZ} icode).
 */
public class ClassTdz extends Instruction {
    private final String name;
    private final boolean parameter;

    public ClassTdz(String name, boolean parameter) {
        this.name = name;
        this.parameter = parameter;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        throw ClassRuntime.tdzError(name, parameter);
    }

    @Override
    public int stackChange() {
        return 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(this, "name", name, "parameter", parameter);
    }
}
