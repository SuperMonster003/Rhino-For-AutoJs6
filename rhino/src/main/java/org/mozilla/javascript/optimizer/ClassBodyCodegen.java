/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.optimizer;

import org.mozilla.classfile.ByteCode;
import org.mozilla.classfile.ClassFileWriter;
import org.mozilla.javascript.ClassOp;
import org.mozilla.javascript.Kit;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Token;

/**
 * AutoJs6 class syntax: the compiler side of the {@link Token#CLASS} IR nodes (ROADMAP P2.3). Each
 * node becomes calls of the {@code ClassRuntime} primitives, which take the values the expression
 * leaves on the operand stack first.
 */
final class ClassBodyCodegen {

    private static final String RUNTIME = "org/mozilla/javascript/ClassRuntime";
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
            default:
                throw Kit.codeBug();
        }
    }

    /** Unwraps the this value on the stack for a super method call. */
    static void receiver(ClassFileWriter cfw) {
        invoke(cfw, "receiver", "(" + OBJECT + ")" + OBJECT);
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

    private static void pushNewTarget(BodyCodegen bc) {
        if (bc.isGenerator) {
            // newTargetLocal holds the generator state in a generator, and new.target is undefined.
            Codegen.pushUndefined(bc.cfw);
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
