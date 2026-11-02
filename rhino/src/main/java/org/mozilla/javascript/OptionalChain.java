/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.ArrayList;
import java.util.List;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.XmlMemberGet;

/**
 * AutoJs6: optional chains that short circuit as a whole (appendix C Q55). Not related to classes;
 * a fix of an upstream gap, which tested each member after the {@code ?.} on its own.
 *
 * <p>The parser types the members of a chain as {@link Token#QUESTION_DOT} (a call: {@link
 * FunctionCall#isOptionalCall}) and marks each {@code ?.} with {@link Node#OPTIONAL_CHAINING}. The
 * outermost member reaches {@link IRFactory#transform} first, {@link #lower} then transforms it as
 * usual: each member gets its target from {@link #target}, which turns a {@code ?.} into a point of
 * the chain and hands the member a read of the local that holds the value before it. The members
 * are plain ones from then on, so {@code a?.b.c} is {@code a} and {@code t.b.c}, and what the
 * members do around their targets (super, private names, spread arguments) stays as it is.
 *
 * <p>Each point becomes an {@link ClassOp#OPTIONAL_CHAIN} around the rest of the chain. The call of
 * {@code f?.()} keeps the callee in the local as a lookup result, so the this value of {@code
 * a.b?.()} survives the test and a name is not a direct eval. A chain in parentheses is a member of
 * no outer chain; as a callee ({@code (a?.b)()}) the backends make it leave a lookup result too.
 * The E4X {@code ..} and {@code .()} operators, which the proposal does not know, end a chain.
 */
final class OptionalChain {

    private final IRFactory factory;

    /** The points of the chain being lowered, from the first {@code ?.} on. */
    private List<Point> points;

    /** The member that {@link #member} is transforming, which starts no chain of its own. */
    private AstNode continued;

    OptionalChain(IRFactory factory) {
        this.factory = factory;
    }

    private static final class Point {
        final Node head;
        final Node localBlock;
        final boolean call;

        Point(Node head, Node localBlock, boolean call) {
            this.head = head;
            this.localBlock = localBlock;
            this.call = call;
        }

        Node around(Node rest, boolean delete) {
            Node chain = ClassLowering.newClassNode(ClassOp.OPTIONAL_CHAIN);
            ClassOp.of(chain).optionalCall = call;
            chain.putProp(Node.LOCAL_BLOCK_PROP, localBlock);
            chain.addChildToBack(head);
            chain.addChildToBack(rest);
            chain.addChildToBack(new Node(delete ? Token.TRUE : Token.UNDEFINED));
            return chain;
        }
    }

    private static boolean isMember(AstNode node) {
        return node.getType() == Token.QUESTION_DOT
                || (node instanceof FunctionCall && ((FunctionCall) node).isOptionalCall());
    }

    /** Whether {@code node} is the outermost member of a chain, for {@link #lower}. */
    boolean starts(AstNode node) {
        if (!isMember(node)) {
            return false;
        }
        if (node == continued) {
            continued = null;
            return false;
        }
        return true;
    }

    /** Transforms the chain that ends in {@code node}. */
    Node lower(AstNode node) {
        return lower(node, false);
    }

    /** {@code delete} of a chain, which is true when it short circuits; null for other operands. */
    Node delete(AstNode operand) {
        while (operand instanceof ParenthesizedExpression) {
            operand = ((ParenthesizedExpression) operand).getExpression();
        }
        return isMember(operand) ? lower(operand, true) : null;
    }

    private Node lower(AstNode node, boolean delete) {
        List<Point> outer = points;
        points = new ArrayList<>();
        try {
            Node chain = member(node);
            if (delete) {
                chain = IRFactory.createUnary(Token.DELPROP, chain);
            }
            for (int i = points.size() - 1; i >= 0; i--) {
                chain = points.get(i).around(chain, delete);
            }
            return chain;
        } finally {
            points = outer;
        }
    }

    private Node member(AstNode node) {
        continued = node;
        return factory.transform(node);
    }

    /**
     * The target of a member access or call: for a member of a chain, the value of the chain so far
     * or, after a {@code ?.}, the read of the local that holds it. Runs before the member looks at
     * its type, which becomes the one of a plain member here.
     */
    Node target(AstNode node, AstNode target) {
        if (!isMember(node)) {
            return factory.transform(target);
        }
        Node value = isMember(target) ? member(target) : factory.transform(target);
        boolean call = node instanceof FunctionCall;
        if (!call) {
            node.setType(plainType(node));
        }
        if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) != 1) {
            return value;
        }
        Node localBlock = new Node(Token.LOCAL_BLOCK);
        points.add(new Point(value, localBlock, call));
        Node read;
        if (call) {
            read = ClassLowering.newClassNode(ClassOp.OPTIONAL_CALLEE);
            if (value.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                read.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1);
            }
        } else {
            read = new Node(Token.LOCAL_LOAD);
        }
        read.putProp(Node.LOCAL_BLOCK_PROP, localBlock);
        return read;
    }

    private static int plainType(AstNode node) {
        if (node instanceof ElementGet) {
            return Token.GETELEM;
        }
        return node instanceof XmlMemberGet ? Token.DOT : Token.GETPROP;
    }
}
