/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.interpreterv2.instruction;

import org.mozilla.javascript.CallFrameV2;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.UniqueTag;

/** AutoJs6 class syntax: stack helpers of the class instructions. */
final class ClassValues {
    private ClassValues() {}

    /** Pops the top of the stack, boxing a number held in the double stack. */
    static Object pop(CallFrameV2 frame) {
        Object value = frame.peek();
        if (value == UniqueTag.DOUBLE_MARK) {
            value = ScriptRuntime.wrapNumber(frame.peekDouble());
        }
        frame.pop();
        return value;
    }
}
