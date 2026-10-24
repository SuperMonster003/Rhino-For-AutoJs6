/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.ClassNode;

/**
 * AutoJs6 class syntax: lowering of {@link ClassNode} into IR (ROADMAP P2.1). Until P2 lands, a
 * class that reaches {@link IRFactory} is reported with a readable "not supported yet" error, so
 * that scripts fail with a clear message instead of a syntax error.
 */
final class ClassLowering {

    private ClassLowering() {}

    /** Transforms a {@link ClassNode}. */
    static Node transform(Parser parser, AstNode node) {
        reportNotSupported(parser, node, "msg.class.not.supported");
        return new Node(Token.EMPTY);
    }

    /** Reports an error at the position of the given node during IR transformation. */
    static void reportNotSupported(Parser parser, AstNode node, String key) {
        IRFactory.AstNodePosition position = (IRFactory.AstNodePosition) parser.currentPos;
        position.push(node);
        try {
            parser.reportError(key, node.getAbsolutePosition(), node.getLength());
        } finally {
            position.pop();
        }
    }
}
