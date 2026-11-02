/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AwaitExpression;
import org.mozilla.javascript.ast.ForAwaitOfLoop;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.ReturnStatement;
import org.mozilla.javascript.ast.ScriptNode;
import org.mozilla.javascript.ast.Yield;

/**
 * AutoJs6 async: lowering of async functions into IR (ROADMAP T7). An async function is a generator
 * for the rest of the compiler: its body has the state machine of a generator, and {@code await} is
 * a {@link Token#YIELD} of a {@link ClassOp#AWAIT}, which marks the value for the driver and
 * carries the line. {@link AsyncRuntime#start} takes the place of the creation of the generator
 * object.
 *
 * <p>An async function has no generator object to throw at its caller: the parameters are
 * initialized in the body, so that an error there rejects the promise. An async generator
 * initializes them like a generator, before the object exists.
 */
final class AsyncLowering {

    private AsyncLowering() {}

    /** Makes an async function a generator before its body is transformed. */
    static void prepare(FunctionNode fn) {
        if (fn.isAsync()) {
            fn.setIsAsyncGenerator(fn.isES6Generator());
            fn.setIsES6Generator();
            fn.setRequiresActivation();
        }
    }

    /** {@link JSDescriptor#asyncKind()} of a function. */
    static int kind(ScriptNode scriptOrFn) {
        if (!(scriptOrFn instanceof FunctionNode) || !((FunctionNode) scriptOrFn).isAsync()) {
            return AsyncRuntime.NOT_ASYNC;
        }
        FunctionNode fn = (FunctionNode) scriptOrFn;
        int kind =
                fn.isAsyncGenerator() ? AsyncRuntime.ASYNC_GENERATOR : AsyncRuntime.ASYNC_FUNCTION;
        Name name = fn.getFunctionName();
        return fn.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION
                        && name != null
                        && name.length() != 0
                        && fn.getSymbol(name.getIdentifier()) == null
                ? kind | AsyncRuntime.OWN_NAME
                : kind;
    }

    /**
     * Whether the parameters of an async function hold a direct eval: they then run in a parameter
     * scope, so that {@link AsyncRuntime#checkEvalInParameters} sees it.
     */
    static boolean evalInParameters(FunctionNode fn) {
        if (!fn.isAsync()) {
            return false;
        }
        boolean[] found = new boolean[1];
        NodeVisitor visitor =
                node -> {
                    if (node instanceof FunctionCall) {
                        AstNode target = ((FunctionCall) node).getTarget();
                        found[0] |=
                                target instanceof Name
                                        && "eval".equals(((Name) target).getIdentifier());
                    }
                    return !(node instanceof FunctionNode)
                            || ((FunctionNode) node).getFunctionType()
                                    == FunctionNode.ARROW_FUNCTION;
                };
        for (AstNode param : fn.getParams()) {
            param.visit(visitor);
        }
        if (fn.getDefaultParams() != null) {
            for (Object value : fn.getDefaultParams()) {
                if (value instanceof AstNode) {
                    ((AstNode) value).visit(visitor);
                }
            }
        }
        return found[0];
    }

    /** Moves the initialization of the parameters of an async function into the body. */
    static void paramsIntoBody(FunctionNode fn, Node body) {
        Node block = fn.getGeneratorParamInitBlock();
        if (!fn.isAsync() || fn.isAsyncGenerator() || block == null) {
            return;
        }
        if (fn.getFirstChild() == block) {
            fn.removeChild(block);
        }
        fn.setGeneratorParamInitBlock(null);
        body.addChildToFront(block);
    }

    static Node await(IRFactory factory, AwaitExpression node) {
        return await(factory.transform(node.getOperand()), node.getLineno(), node.getColumn());
    }

    private static Node await(Node value, int lineno, int column) {
        Node await = ClassLowering.newClassNode(ClassOp.AWAIT);
        await.addChildToBack(value);
        await.setLineColumnNumber(lineno, column);
        return new Node(Token.YIELD, await, lineno, column);
    }

    private static boolean inAsyncGenerator(Parser parser) {
        ScriptNode function = parser.currentScriptOrFn;
        return function instanceof FunctionNode && ((FunctionNode) function).isAsyncGenerator();
    }

    /** {@code yield v} of an async generator awaits {@code v}. */
    static Node yieldValue(Parser parser, Yield node, Node value) {
        if (node.getType() != Token.YIELD || !inAsyncGenerator(parser)) {
            return value;
        }
        Node operand = value != null ? value : new Node(Token.UNDEFINED);
        return await(operand, node.getLineno(), node.getColumn());
    }

    /** {@code return v} of an async generator awaits {@code v}. */
    static Node returnValue(Parser parser, ReturnStatement node, Node value) {
        if (value == null || !inAsyncGenerator(parser)) {
            return value;
        }
        return await(value, node.getLineno(), node.getColumn());
    }

    /**
     * The nodes that replace the enumeration of a {@code for await} loop, see {@link ForAwait}: the
     * initialization, the condition and the value; null for the other loops.
     */
    static Node[] forAwait(AstNode loop, Node localBlock, Node iterable) {
        if (!(loop instanceof ForAwaitOfLoop)) {
            return null;
        }
        localBlock.putIntProp(Node.CLASS_PROP, 1);
        int lineno = loop.getLineno(), column = loop.getColumn();
        Node init = new Node(Token.EXPR_VOID, step(localBlock, ForAwait.OPEN, iterable));
        Node next = await(step(localBlock, ForAwait.NEXT, null), lineno, column);
        return new Node[] {
            init, step(localBlock, ForAwait.STEP, next), step(localBlock, ForAwait.VALUE, null)
        };
    }

    /** The closing of a {@code for await} loop, like {@link BindingLowering#forOf}. */
    static Node forAwaitClosing(AstNode ast, Node localBlock, Node loop) {
        if (!(ast instanceof ForAwaitOfLoop)) {
            return loop;
        }
        int lineno = loop.getLineno(), column = loop.getColumn();
        Node quiet = await(step(localBlock, ForAwait.CLOSE_QUIET, null), lineno, column);
        Node close = await(step(localBlock, ForAwait.CLOSE, null), lineno, column);
        Node check = step(localBlock, ForAwait.CLOSE_CHECK, close);
        return new Node(Token.BLOCK, BindingLowering.closing(loop, quiet, check, lineno, column));
    }

    private static Node step(Node localBlock, int step, Node value) {
        Node node = ClassLowering.newClassNode(ClassOp.FOR_AWAIT);
        ClassOp.of(node).step = step;
        node.putProp(Node.LOCAL_BLOCK_PROP, localBlock);
        if (value != null) {
            node.addChildToBack(value);
        }
        return node;
    }
}
