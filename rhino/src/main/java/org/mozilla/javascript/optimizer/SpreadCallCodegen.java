/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import org.mozilla.classfile.ByteCode;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.SpreadCall;

/**
 * AutoJs6: the compiler side of spread arguments in calls (ROADMAP P6.6, see {@link SpreadCall}).
 * Every call and {@code new} gets its arguments from {@code BodyCodegen.generateCallArgArray},
 * which builds the argument array of a lowered call from its array literal, or from the spread
 * value of {@code f(...a)} (P6.9).
 */
final class SpreadCallCodegen {

    private SpreadCallCodegen() {}

    /**
     * Generates the argument array of a lowered call and returns true, or returns false when the
     * arguments are not spread.
     */
    static boolean generateArgs(BodyCodegen bc, Node node, Node argChild) {
        if (!SpreadCall.isSpread(argChild)) {
            return false;
        }
        Node source = SpreadCall.singleSpread(argChild);
        if (source != null) {
            bc.generateExpression(source, node);
            bc.cfw.addALoad(bc.contextLocal);
            bc.cfw.addALoad(bc.variableObjectLocal);
            bc.cfw.addInvoke(
                    ByteCode.INVOKESTATIC,
                    "org/mozilla/javascript/SpreadCall",
                    "spreadArgs",
                    "(Ljava/lang/Object;Lorg/mozilla/javascript/Context;"
                            + "Lorg/mozilla/javascript/VarScope;)[Ljava/lang/Object;");
            return true;
        }
        bc.generateExpression(argChild.getFirstChild(), node);
        bc.cfw.addInvoke(
                ByteCode.INVOKESTATIC,
                "org/mozilla/javascript/SpreadCall",
                "args",
                "(Ljava/lang/Object;)[Ljava/lang/Object;");
        return true;
    }
}
