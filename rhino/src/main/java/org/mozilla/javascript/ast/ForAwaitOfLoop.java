/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.ast;

import org.mozilla.javascript.Token;

/**
 * AutoJs6 async: AST node for a {@code for await (... of ...)} loop inside an async function. Node
 * type is {@link Token#FOR}, like the other for-in and for-of loops; {@link #isForOf()} is always
 * true.
 */
public class ForAwaitOfLoop extends ForInLoop {

    private int awaitPosition = -1;

    {
        isForOf = true;
    }

    public ForAwaitOfLoop() {}

    public ForAwaitOfLoop(int pos) {
        super(pos);
    }

    public ForAwaitOfLoop(int pos, int len) {
        super(pos, len);
    }

    /** Returns position of the "await" keyword, relative to this node, or -1. */
    public int getAwaitPosition() {
        return awaitPosition;
    }

    /** Sets position of the "await" keyword, relative to this node. */
    public void setAwaitPosition(int awaitPosition) {
        this.awaitPosition = awaitPosition;
    }

    @Override
    public String toSource(int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append(makeIndent(depth));
        sb.append("for await (");
        sb.append(iterator.toSource(0));
        sb.append(" of ");
        sb.append(iteratedObject.toSource(0));
        sb.append(") ");
        if (body.getType() == Token.BLOCK) {
            sb.append(body.toSource(depth).trim()).append("\n");
        } else {
            sb.append("\n").append(body.toSource(depth + 1));
        }
        return sb.toString();
    }
}
