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
 * AutoJs6 class syntax: creates the prototype and the constructor of a class (the {@code
 * CLASS_DEFINE} icode). Stack: [heritage] -&gt; F.
 */
public class ClassDefine extends Instruction {
    private final int fnIndex;
    private final boolean hasHeritage;

    public ClassDefine(int fnIndex, boolean hasHeritage) {
        this.fnIndex = fnIndex;
        this.hasHeritage = hasHeritage;
    }

    @Override
    public void interpret(Context cx, CallFrameV2 frame) {
        Object heritage = hasHeritage ? ClassValues.pop(frame) : null;
        var constructor = frame.fnOrScript.getDescriptor().getFunction(fnIndex);
        frame.push(ClassRuntime.defineClass(heritage, hasHeritage, constructor, cx, frame.scope));
        frame.pc += 1;
    }

    @Override
    public int stackChange() {
        return hasHeritage ? 0 : 1;
    }

    @Override
    public String toDebugString() {
        return InstructionFormatter.formatInstruction(
                this, "fnIndex", fnIndex, "hasHeritage", hasHeritage);
    }
}
