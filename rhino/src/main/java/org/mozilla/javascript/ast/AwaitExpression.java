/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.ast;

import org.mozilla.javascript.Token;

/**
 * AutoJs6 async: AST node for an {@code await} expression inside an async function. Node type is
 * {@link Token#AWAIT}.
 *
 * <pre><i>AwaitExpression</i> :
 *   <b>await</b> UnaryExpression</pre>
 */
public class AwaitExpression extends AstNode {

    private AstNode operand;

    {
        type = Token.AWAIT;
    }

    public AwaitExpression() {}

    public AwaitExpression(int pos) {
        super(pos);
    }

    public AwaitExpression(int pos, int len, AstNode operand) {
        super(pos, len);
        setOperand(operand);
    }

    /** Returns the awaited expression. */
    public AstNode getOperand() {
        return operand;
    }

    /**
     * Sets the awaited expression, and sets its parent to this node.
     *
     * @throws IllegalArgumentException if {@code operand} is {@code null}
     */
    public void setOperand(AstNode operand) {
        assertNotNull(operand);
        this.operand = operand;
        operand.setParent(this);
    }

    @Override
    public boolean hasSideEffects() {
        return true;
    }

    @Override
    public String toSource(int depth) {
        return makeIndent(depth) + "await " + operand.toSource(0);
    }

    /** Visits this node, then the operand. */
    @Override
    public void visit(NodeVisitor v) {
        if (v.visit(this)) {
            operand.visit(v);
        }
    }
}
