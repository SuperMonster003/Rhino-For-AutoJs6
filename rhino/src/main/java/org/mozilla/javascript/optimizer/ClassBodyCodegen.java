/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import org.mozilla.classfile.ByteCode;
import org.mozilla.classfile.ClassFileWriter;
import org.mozilla.javascript.ClassOp;
import org.mozilla.javascript.ForAwait;
import org.mozilla.javascript.Kit;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.ScriptNode;

/**
 * AutoJs6 class syntax: the compiler side of the {@link Token#CLASS} IR nodes (ROADMAP P2.3). Each
 * node becomes calls of the {@code ClassRuntime} primitives, which take the values the expression
 * leaves on the operand stack first.
 */
final class ClassBodyCodegen {

    private static final String RUNTIME = "org/mozilla/javascript/ClassRuntime";
    private static final String LOOKUP_RESULT = "org/mozilla/javascript/ScriptRuntime$LookupResult";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String CONTEXT_SCOPE =
            "Lorg/mozilla/javascript/Context;Lorg/mozilla/javascript/VarScope;";

    private ClassBodyCodegen() {}

    /** Generates a {@link Token#CLASS} expression, which leaves one object on the stack. */
    static void generate(BodyCodegen bc, Node node) {
        ClassFileWriter cfw = bc.cfw;
        ClassOp op = ClassOp.of(node);
        Node child = node.getFirstChild();
        switch (op.getKind()) {
            case ClassOp.DEFINE:
                {
                    boolean hasHeritage = op.hasHeritage();
                    if (hasHeritage) {
                        bc.generateExpression(child, node);
                        child = child.getNext();
                    } else {
                        cfw.add(ByteCode.ACONST_NULL);
                    }
                    cfw.addPush(hasHeritage);
                    pushDescriptor(bc, child);
                    pushContextAndScope(bc);
                    invoke(
                            cfw,
                            "defineClass",
                            "("
                                    + OBJECT
                                    + "Z"
                                    + Codegen.DESCRIPTOR_CLASS_SIGNATURE
                                    + CONTEXT_SCOPE
                                    + ")"
                                    + OBJECT);
                    for (Node element = child.getNext();
                            element != null;
                            element = element.getNext()) {
                        Node key = element.getFirstChild();
                        bc.generateExpression(key, element);
                        if (key.getNext() == null) {
                            cfw.add(ByteCode.ACONST_NULL);
                        } else {
                            pushDescriptor(bc, key.getNext());
                        }
                        cfw.addPush(element.getExistingIntProp(Node.CLASS_PROP));
                        pushContextAndScope(bc);
                        invoke(
                                cfw,
                                "defineElement",
                                "("
                                        + OBJECT
                                        + OBJECT
                                        + Codegen.DESCRIPTOR_CLASS_SIGNATURE
                                        + "I"
                                        + CONTEXT_SCOPE
                                        + ")"
                                        + OBJECT);
                    }
                    break;
                }
            case ClassOp.SUPER_CALL:
                {
                    cfw.addALoad(bc.thisObjLocal);
                    invoke(cfw, "superConstructor", "(" + OBJECT + ")" + OBJECT);
                    String method;
                    String args;
                    if (op.hasArrayArguments()) {
                        bc.generateExpression(child, node);
                        method = "superCallArray";
                        args = OBJECT;
                    } else {
                        bc.callArgArray(node, child);
                        method = "superCall";
                        args = "[Ljava/lang/Object;";
                    }
                    pushContextAndScope(bc);
                    cfw.addALoad(bc.thisObjLocal);
                    pushNewTarget(bc);
                    invoke(
                            cfw,
                            method,
                            "(" + OBJECT + args + CONTEXT_SCOPE + OBJECT + OBJECT + ")" + OBJECT);
                    break;
                }
            case ClassOp.THIS:
                cfw.addALoad(bc.thisObjLocal);
                invoke(cfw, "thisValue", "(" + OBJECT + ")" + OBJECT);
                break;
            case ClassOp.TDZ:
                cfw.addPush(op.getName());
                cfw.addPush(op.isParameter());
                invoke(cfw, "throwTdz", "(Ljava/lang/String;Z)" + OBJECT);
                break;
            case ClassOp.ITERATOR_REST:
                bc.generateExpression(child, node);
                pushContextAndScope(bc);
                invoke(cfw, "iteratorRest", "(" + OBJECT + CONTEXT_SCOPE + ")" + OBJECT);
                break;
            case ClassOp.REQUIRE_OBJECT:
                bc.generateExpression(child, node);
                invoke(cfw, "requireObjectCoercible", "(" + OBJECT + ")" + OBJECT);
                break;
            case ClassOp.PARAM_SCOPE:
                cfw.addPush(op.getName());
                pushContextAndScope(bc);
                invoke(cfw, "paramScope", "(Ljava/lang/String;" + CONTEXT_SCOPE + ")" + OBJECT);
                break;
            case ClassOp.ITERATOR_CLOSE:
                {
                    Node localBlock = (Node) node.getProp(Node.LOCAL_BLOCK_PROP);
                    cfw.addALoad(localBlock.getExistingIntProp(Node.LOCAL_PROP));
                    cfw.addPush(op.isQuiet());
                    pushContextAndScope(bc);
                    invoke(cfw, "iteratorClose", "(" + OBJECT + "Z" + CONTEXT_SCOPE + ")" + OBJECT);
                    break;
                }
            case ClassOp.CTOR_CHECK:
                pushNewTarget(bc);
                cfw.addALoad(bc.funObjLocal);
                invoke(cfw, "checkConstructorCall", "(" + OBJECT + OBJECT + ")" + OBJECT);
                break;
            case ClassOp.PRIVATE_NAME:
                cfw.addPush(op.getName());
                invoke(cfw, "newPrivateName", "(Ljava/lang/String;)" + OBJECT);
                break;
            case ClassOp.INIT_FIELDS:
                cfw.addALoad(bc.thisObjLocal);
                cfw.addALoad(bc.funObjLocal);
                cfw.addALoad(bc.contextLocal);
                invoke(
                        cfw,
                        "initializeInstanceElements",
                        "(" + OBJECT + OBJECT + "Lorg/mozilla/javascript/Context;)" + OBJECT);
                break;
            case ClassOp.STATIC_INIT:
                bc.generateExpression(child, node);
                cfw.addALoad(bc.contextLocal);
                invoke(
                        cfw,
                        "staticInit",
                        "(" + OBJECT + "Lorg/mozilla/javascript/Context;)" + OBJECT);
                break;
            case ClassOp.DESTRUCTURE:
                {
                    int local =
                            ((Node) node.getProp(Node.LOCAL_BLOCK_PROP))
                                    .getExistingIntProp(Node.LOCAL_PROP);
                    if (child != null) {
                        bc.generateExpression(child, node);
                    } else {
                        cfw.addALoad(local);
                    }
                    cfw.addPush(op.getStep());
                    pushContextAndScope(bc);
                    cfw.addInvoke(
                            ByteCode.INVOKESTATIC,
                            "org/mozilla/javascript/ArrayDestructuring",
                            "destructure",
                            "(" + OBJECT + "I" + CONTEXT_SCOPE + ")" + OBJECT);
                    if (child != null) {
                        cfw.add(ByteCode.DUP);
                        cfw.addAStore(local);
                    }
                    break;
                }
            case ClassOp.AWAIT:
                bc.generateExpression(child, node);
                cfw.addPush(Math.max(node.getLineno(), 0));
                cfw.addInvoke(
                        ByteCode.INVOKESTATIC,
                        "org/mozilla/javascript/AsyncRuntime",
                        "await",
                        "(" + OBJECT + "I)" + OBJECT);
                break;
            case ClassOp.FOR_AWAIT:
                {
                    int local =
                            ((Node) node.getProp(Node.LOCAL_BLOCK_PROP))
                                    .getExistingIntProp(Node.LOCAL_PROP);
                    int step = op.getStep();
                    if (child != null) {
                        bc.generateExpression(child, node);
                    } else {
                        cfw.add(ByteCode.ACONST_NULL);
                    }
                    if (step == ForAwait.OPEN) {
                        cfw.add(ByteCode.ACONST_NULL);
                    } else {
                        cfw.addALoad(local);
                    }
                    cfw.addPush(step);
                    pushContextAndScope(bc);
                    cfw.addInvoke(
                            ByteCode.INVOKESTATIC,
                            "org/mozilla/javascript/ForAwait",
                            "step",
                            "(" + OBJECT + OBJECT + "I" + CONTEXT_SCOPE + ")" + OBJECT);
                    if (step == ForAwait.OPEN) {
                        cfw.add(ByteCode.DUP);
                        cfw.addAStore(local);
                    }
                    break;
                }
            case ClassOp.LOGICAL_NAME:
                logicalName(bc, child, node);
                break;
            case ClassOp.OPTIONAL_CHAIN:
                optionalChain(bc, node, false);
                break;
            default:
                throw Kit.codeBug();
        }
    }

    /**
     * Generates a callee of a {@link Token#CALL} that is a {@link Token#CLASS} node with a lookup
     * result of its own: a chain in parentheses keeps the this value of its last member.
     */
    static boolean callee(BodyCodegen bc, Node node) {
        if (node.getType() != Token.CLASS) {
            return false;
        }
        switch (ClassOp.of(node).getKind()) {
            case ClassOp.OPTIONAL_CALLEE:
                bc.cfw.addALoad(
                        ((Node) node.getProp(Node.LOCAL_BLOCK_PROP))
                                .getExistingIntProp(Node.LOCAL_PROP));
                bc.cfw.add(ByteCode.CHECKCAST, LOOKUP_RESULT);
                return true;
            case ClassOp.OPTIONAL_CHAIN:
                optionalChain(bc, node, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * An {@link ClassOp#OPTIONAL_CHAIN}, which leaves one object or, as a callee, a lookup result.
     * The local is allocated after the first child, which may yield before it has a value.
     */
    private static void optionalChain(BodyCodegen bc, Node node, boolean callee) {
        ClassFileWriter cfw = bc.cfw;
        Node head = node.getFirstChild();
        Node rest = head.getNext();
        Node shortValue = rest.getNext();
        boolean optionalCall = ClassOp.of(node).isOptionalCall();
        if (optionalCall) {
            bc.generateLookupResult(head, node);
        } else {
            bc.generateExpression(head, node);
        }
        int local = bc.newChainLocal();
        Node localBlock = (Node) node.getProp(Node.LOCAL_BLOCK_PROP);
        localBlock.putIntProp(Node.LOCAL_PROP, local);
        cfw.add(ByteCode.DUP);
        cfw.addAStore(local);
        if (optionalCall) {
            invoke(cfw, "optionalCallee", "(L" + LOOKUP_RESULT + ";)" + OBJECT);
        }
        cfw.addInvoke(
                ByteCode.INVOKESTATIC,
                "org/mozilla/javascript/optimizer/OptRuntime",
                "isNullOrUndefined",
                "(" + OBJECT + ")Z");
        int shortCircuit = cfw.acquireLabel();
        cfw.add(ByteCode.IFNE, shortCircuit);
        int stack = cfw.getStackTop();
        if (callee) {
            bc.generateLookupResult(rest, node);
        } else {
            bc.generateExpression(rest, node);
        }
        int end = cfw.acquireLabel();
        cfw.add(ByteCode.GOTO, end);
        cfw.markLabel(shortCircuit, stack);
        if (callee) {
            bc.generateLookupResult(shortValue, node);
        } else {
            bc.generateExpression(shortValue, node);
        }
        cfw.markLabel(end);
        bc.releaseChainLocal(local);
        localBlock.removeProp(Node.LOCAL_PROP);
    }

    /**
     * The read of a compound assignment marked by {@link ClassOp#isBoundRead}: the value of the
     * name in the scope on the stack, which stays there for the assignment.
     */
    static boolean boundRead(BodyCodegen bc, Node node) {
        if (!ClassOp.isBoundRead(node)) {
            return false;
        }
        ClassFileWriter cfw = bc.cfw;
        cfw.add(ByteCode.DUP);
        pushContextAndScope(bc);
        cfw.addPush(node.getString());
        invoke(cfw, "boundValue", "(" + OBJECT + CONTEXT_SCOPE + "Ljava/lang/String;)" + OBJECT);
        return true;
    }

    /**
     * A {@link ClassOp#LOGICAL_NAME}: the binding ({@code ClassRuntime.logicalBind}) stays under
     * the value read from it, and the assignment, unless the operator short circuits, writes to the
     * scope it gives.
     */
    private static void logicalName(BodyCodegen bc, Node logical, Node node) {
        Node read = logical.getFirstChild();
        Node write = read.getNext();
        if (!ClassOp.isLogicalName(read, write)) {
            bc.generateExpression(logical, node);
            return;
        }
        ClassFileWriter cfw = bc.cfw;
        Node bind = write.getFirstChild();
        String name = bind.getString();
        pushContextAndScope(bc);
        cfw.addPush(name);
        invoke(cfw, "logicalBind", "(" + CONTEXT_SCOPE + "Ljava/lang/String;)" + OBJECT);
        cfw.add(ByteCode.DUP);
        pushContextAndScope(bc);
        cfw.addPush(name);
        invoke(cfw, "boundValue", "(" + OBJECT + CONTEXT_SCOPE + "Ljava/lang/String;)" + OBJECT);
        cfw.add(ByteCode.DUP);
        int shortCircuit = cfw.acquireLabel();
        if (logical.getType() == Token.NULLISH_COALESCING) {
            cfw.addInvoke(
                    ByteCode.INVOKESTATIC,
                    "org/mozilla/javascript/optimizer/OptRuntime",
                    "isNullOrUndefined",
                    "(" + OBJECT + ")Z");
            cfw.add(ByteCode.IFEQ, shortCircuit);
        } else {
            // as the logical operators of BodyCodegen, whose linkers convert numbers inline
            cfw.addInvokeDynamic(
                    "MATH:TOBOOLEAN", Signatures.MATH_TO_BOOLEAN, Bootstrapper.BOOTSTRAP_HANDLE);
            cfw.add(logical.getType() == Token.AND ? ByteCode.IFEQ : ByteCode.IFNE, shortCircuit);
        }
        int stack = cfw.getStackTop();
        cfw.add(ByteCode.POP);
        pushContextAndScope(bc);
        cfw.addPush(name);
        invoke(
                cfw,
                "logicalTarget",
                "("
                        + OBJECT
                        + CONTEXT_SCOPE
                        + "Ljava/lang/String;)Lorg/mozilla/javascript/VarScope;");
        bc.generateExpression(bind.getNext(), write);
        pushContextAndScope(bc);
        cfw.addPush(name);
        cfw.addInvoke(
                ByteCode.INVOKESTATIC,
                "org/mozilla/javascript/ScriptRuntime",
                write.getType() == Token.STRICT_SETNAME ? "strictSetName" : "setName",
                "(Lorg/mozilla/javascript/VarScope;"
                        + OBJECT
                        + CONTEXT_SCOPE
                        + "Ljava/lang/String;)"
                        + OBJECT);
        int end = cfw.acquireLabel();
        cfw.add(ByteCode.GOTO, end);
        cfw.markLabel(shortCircuit, stack);
        cfw.add(ByteCode.SWAP);
        cfw.add(ByteCode.POP);
        cfw.markLabel(end);
    }

    /** Unwraps the this value on the stack for a super method call. */
    static void receiver(ClassFileWriter cfw) {
        invoke(cfw, "receiver", "(" + OBJECT + ")" + OBJECT);
    }

    /** Replaces the scope on the stack by the home object of the current function. */
    static void homeObject(ClassFileWriter cfw) {
        invoke(cfw, "homeObject", "(" + OBJECT + ")Lorg/mozilla/javascript/Scriptable;");
    }

    /**
     * Wraps the child in {@code TO_OBJECT} in place and returns the wrapper: for the optimizer, a
     * callee whose value is a double (a number variable, an assignment or increment of one).
     * Upstream only rewrote the children of the callee, so such a callee reached the call as a
     * double (VerifyError, or a failed assertion for an assignment).
     */
    static Node toObject(Node parent, Node child) {
        Node object = new Node(Token.TO_OBJECT);
        parent.replaceChild(child, object);
        object.addChildToBack(child);
        return object;
    }

    private static void pushDescriptor(BodyCodegen bc, Node function) {
        int fnIndex = function.getExistingIntProp(Node.FUNCTION_PROP);
        OptFunctionNode ofn = OptFunctionNode.get(bc.scriptOrFn, fnIndex);
        ClassFileWriter cfw = bc.cfw;
        // The descriptors array always lives on the main class (see BodyCodegen.visitFunction).
        cfw.add(
                ByteCode.GETSTATIC,
                bc.codegen.mainClassName,
                Codegen.DESCRIPTORS_FIELD_NAME,
                Codegen.DESCRIPTORS_FIELD_SIGNATURE);
        cfw.addPush(bc.codegen.getIndex(ofn.fnode));
        cfw.add(ByteCode.AALOAD);
    }

    private static void pushContextAndScope(BodyCodegen bc) {
        bc.cfw.addALoad(bc.contextLocal);
        bc.cfw.addALoad(bc.variableObjectLocal);
    }

    /**
     * The method that creates the activation of a generator. An async arrow function is a generator
     * here (AsyncLowering).
     */
    static String activationMethod(ScriptNode scriptOrFn) {
        return isArrow(scriptOrFn) ? "createArrowFunctionActivation" : "createFunctionActivation";
    }

    private static boolean isArrow(ScriptNode scriptOrFn) {
        return scriptOrFn instanceof FunctionNode
                && ((FunctionNode) scriptOrFn).getFunctionType() == FunctionNode.ARROW_FUNCTION;
    }

    /**
     * new.target in a generator, where newTargetLocal holds the generator state: undefined, except
     * for an async arrow function, which has the one of the function around it.
     */
    static void generatorNewTarget(BodyCodegen bc) {
        if (isArrow(bc.scriptOrFn)) {
            bc.cfw.addALoad(bc.funObjLocal);
            bc.cfw.addInvoke(
                    ByteCode.INVOKEVIRTUAL,
                    "org/mozilla/javascript/JSFunction",
                    "getLexicalNewTarget",
                    "()" + OBJECT);
        } else {
            Codegen.pushUndefined(bc.cfw);
        }
    }

    private static void pushNewTarget(BodyCodegen bc) {
        if (bc.isGenerator) {
            generatorNewTarget(bc);
        } else {
            bc.cfw.addALoad(bc.newTargetLocal);
        }
    }

    private static void invoke(ClassFileWriter cfw, String method, String signature) {
        cfw.addInvoke(ByteCode.INVOKESTATIC, RUNTIME, method, signature);
    }

    /** The int constant for a boolean argument. */
    static int flag(boolean value) {
        return value ? ByteCode.ICONST_1 : ByteCode.ICONST_0;
    }

    /**
     * Whether the local of a LOCAL_BLOCK is cleared on entry: the one of a for-of loop is read by
     * the handlers that close its iterator, which GetIterator may not have reached.
     */
    static boolean clearsLocal(BodyCodegen bc, Node localBlock) {
        return bc.isGenerator || localBlock.getIntProp(Node.CLASS_PROP, 0) != 0;
    }

    /**
     * The value of a yield. It comes before the yield point saves the operand stack, because a
     * yield inside it saves the stack as well, and there is one saved stack for a generator.
     */
    static void yieldValue(BodyCodegen bc, Node node) {
        ClassFileWriter cfw = bc.cfw;
        Node child = node.getFirstChild();
        if (child != null) {
            bc.generateExpression(child, node);
        } else {
            Codegen.pushUndefined(cfw);
        }
        if (node.getType() == Token.YIELD_STAR) {
            // We will replace the result with one that signifies we should have a generator
            cfw.add(ByteCode.NEW, "org/mozilla/javascript/ES6Generator$YieldStarResult");
            cfw.add(ByteCode.DUP_X1);
            cfw.add(ByteCode.SWAP);
            cfw.addInvoke(
                    ByteCode.INVOKESPECIAL,
                    "org/mozilla/javascript/ES6Generator$YieldStarResult",
                    "<init>",
                    "(Ljava/lang/Object;)V");
        }
    }

    /**
     * Whether a yield of the generator being compiled is somewhere in {@code node}. A direct call
     * evaluates its arguments twice in the generated code, which a yield point cannot take.
     */
    static boolean yieldsIn(BodyCodegen bc, Node node) {
        return bc.isGenerator && hasYield(node);
    }

    private static boolean hasYield(Node node) {
        int type = node.getType();
        if (type == Token.YIELD || type == Token.YIELD_STAR) {
            return true;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (hasYield(child)) {
                return true;
            }
        }
        return false;
    }
}
