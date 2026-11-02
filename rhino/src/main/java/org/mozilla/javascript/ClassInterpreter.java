/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.Interpreter.ICodeDumpContext;
import org.mozilla.javascript.Interpreter.InstructionClass;
import org.mozilla.javascript.Interpreter.InterpreterState;
import org.mozilla.javascript.Interpreter.NewState;

/**
 * AutoJs6 class syntax: the interpreter side of the {@link Token#CLASS} IR nodes (ROADMAP P2.3).
 *
 * <p>{@link #generate} emits the icodes from {@link CodeGenerator}, the instruction classes run
 * them. Operands:
 *
 * <ul>
 *   <li>{@code CLASS_DEFINE}: index register = constructor function, then a byte, 1 when the
 *       heritage is on the stack. Stack: [heritage] -&gt; F.
 *   <li>{@code CLASS_ELEMENT}: index register = method, initializer or static block function
 *       (unused with {@code ELEMENT_NO_VALUE}), then the element flags byte. Stack: F, key -&gt; F.
 *   <li>{@code CLASS_CTOR_CHECK}, {@code CLASS_THIS}, {@code CLASS_SUPER_FUNC}: no operand, push
 *       one value.
 *   <li>{@code CLASS_TDZ}: string register = the name, then a byte, 1 for a parameter; throws.
 *   <li>{@code CLASS_ITER_REST}: no operand. Stack: iterator -&gt; array of its remaining values.
 *   <li>{@code CLASS_REQUIRE_OBJECT}: no operand. Stack: value -&gt; value (TypeError for null and
 *       undefined).
 *   <li>{@code CLASS_ITER_CLOSE}: index register = the local of the for-of enumeration, then a
 *       byte, 1 when the loop ends with an exception. Pushes undefined, or false in that case.
 *   <li>{@code CLASS_PARAM_SCOPE}: string register = the names, joined by commas. Pushes the object
 *       of the with scope around the initialization of the parameters.
 *   <li>{@code CLASS_SUPER_CALL}: index register = argument count, then a byte, 1 when the only
 *       argument is an array holding the arguments. Stack: super constructor, arguments -&gt; this.
 *   <li>{@code CLASS_PRIVATE_NAME}: string register = the identifier. Pushes a new private name.
 *   <li>{@code CLASS_BLOCK_FUNCTION}: string register = the function. Copies the block binding to
 *       the var binding, pushes undefined.
 *   <li>{@code CLASS_INIT_FIELDS}: no operand. Initializes the instance elements of the class on
 *       this, pushes undefined.
 *   <li>{@code CLASS_STATIC_INIT}: no operand. Stack: F -&gt; F, once the static elements ran.
 *   <li>{@code CLASS_DESTRUCTURE}: index register = the local of the record of an array pattern,
 *       then the step byte ({@link ArrayDestructuring}). {@code OPEN}: value -&gt; record, also
 *       stored in the local; the other steps push their result.
 *   <li>{@code CLASS_BOUND_NAME}: string register = the name. Stack: scope -&gt; value, the read of
 *       a compound assignment after a {@code DUP} of the scope its BINDNAME found ({@link
 *       ClassOp#markBoundRead}).
 *   <li>{@code CLASS_LOGICAL_BIND}: a byte, the step of a {@link ClassOp#LOGICAL_NAME}, and for the
 *       first two the string register = the name. 0: pushes the binding ({@link
 *       ClassRuntime#logicalBind}) and the value read from it, the read itself emits nothing; 1:
 *       binding -&gt; binding, scope the assignment writes to ({@link ClassRuntime#logicalTarget});
 *       2: binding, value -&gt; value.
 *   <li>{@code CLASS_OPTIONAL}: index register = the local of an {@link ClassOp#OPTIONAL_CHAIN}.
 *       Stack: value -&gt; value, also stored in the local.
 *   <li>{@code CLASS_OPTIONAL_CALLEE}: the same for a lookup result, which goes into the local.
 *       Stack: lookup result -&gt; function ({@link ClassRuntime#optionalCallee}).
 * </ul>
 */
final class ClassInterpreter {

    private ClassInterpreter() {}

    /**
     * Emits the read of a compound assignment marked by {@link ClassOp#markBoundRead}, and the read
     * and the BINDNAME of the assignment of a {@link ClassOp#LOGICAL_NAME}.
     */
    static <T extends ScriptOrFn<T>> boolean boundRead(CodeGenerator<T> gen, Node node) {
        if (ClassOp.isLogicalRead(node)) {
            gen.stackChange(1);
            return true;
        }
        if (ClassOp.isBoundBind(node)) {
            gen.addStringOp(Icode.CLASS_LOGICAL_BIND, node.getString());
            gen.addUint8(1);
            gen.stackChange(1);
            return true;
        }
        if (!ClassOp.isBoundRead(node)) {
            return false;
        }
        gen.addIcode(Icode.DUP);
        gen.stackChange(1);
        gen.addStringOp(Icode.CLASS_BOUND_NAME, node.getString());
        return true;
    }

    /**
     * Emits a callee of a {@link Token#CALL} that is a {@link Token#CLASS} node with a lookup
     * result of its own: a chain in parentheses keeps the this value of its last member.
     */
    static <T extends ScriptOrFn<T>> boolean callee(CodeGenerator<T> gen, Node left) {
        if (left.getType() != Token.CLASS) {
            return false;
        }
        switch (ClassOp.of(left).kind) {
            case ClassOp.OPTIONAL_CALLEE:
                {
                    Node localBlock = (Node) left.getProp(Node.LOCAL_BLOCK_PROP);
                    gen.addIndexOp(
                            Token.LOCAL_LOAD, localBlock.getExistingIntProp(Node.LOCAL_PROP));
                    gen.stackChange(2);
                    return true;
                }
            case ClassOp.OPTIONAL_CHAIN:
                optionalChain(gen, left, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * Emits an {@link ClassOp#OPTIONAL_CHAIN}, which leaves one value or, as a callee, a lookup
     * result. A lookup result counts as two values of the stack, like the ones of CodeGenerator.
     */
    private static <T extends ScriptOrFn<T>> void optionalChain(
            CodeGenerator<T> gen, Node node, boolean callee) {
        Node head = node.getFirstChild();
        Node rest = head.getNext();
        Node shortValue = rest.getNext();
        boolean optionalCall = ClassOp.of(node).optionalCall;
        if (optionalCall) {
            gen.generateCallee(head);
            gen.stackChange(-1);
        } else {
            gen.visitExpression(head, 0);
        }
        int local = gen.allocLocal();
        ((Node) node.getProp(Node.LOCAL_BLOCK_PROP)).putIntProp(Node.LOCAL_PROP, local);
        gen.addIndexOp(optionalCall ? Icode.CLASS_OPTIONAL_CALLEE : Icode.CLASS_OPTIONAL, local);
        int shortCircuit = gen.iCodeTop;
        gen.addGotoOp(Icode.IF_NULL_UNDEF);
        gen.stackChange(-1);
        if (callee) {
            gen.generateCallee(rest);
        } else {
            gen.visitExpression(rest, 0);
        }
        int end = gen.iCodeTop;
        gen.addGotoOp(Token.GOTO);
        gen.resolveForwardGoto(shortCircuit);
        gen.stackChange(callee ? -2 : -1);
        if (callee) {
            gen.generateCallee(shortValue);
        } else {
            gen.visitExpression(shortValue, 0);
        }
        gen.resolveForwardGoto(end);
        gen.addIndexOp(Icode.LOCAL_CLEAR, local);
        gen.releaseLocal(local);
    }

    /** Emits the icodes of a {@link Token#CLASS} node, which leaves one value on the stack. */
    static <T extends ScriptOrFn<T>> void generate(CodeGenerator<T> gen, Node node) {
        ClassOp op = ClassOp.of(node);
        Node child = node.getFirstChild();
        switch (op.kind) {
            case ClassOp.DEFINE:
                {
                    if (op.hasHeritage) {
                        gen.visitExpression(child, 0);
                        child = child.getNext();
                    }
                    gen.addIndexOp(
                            Icode.CLASS_DEFINE, child.getExistingIntProp(Node.FUNCTION_PROP));
                    gen.addUint8(op.hasHeritage ? 1 : 0);
                    gen.stackChange(op.hasHeritage ? 0 : 1);
                    for (Node element = child.getNext();
                            element != null;
                            element = element.getNext()) {
                        Node key = element.getFirstChild();
                        Node method = key.getNext();
                        gen.visitExpression(key, 0);
                        gen.addIndexOp(
                                Icode.CLASS_ELEMENT,
                                method == null ? 0 : method.getExistingIntProp(Node.FUNCTION_PROP));
                        gen.addUint8(element.getExistingIntProp(Node.CLASS_PROP));
                        gen.stackChange(-1);
                    }
                    break;
                }
            case ClassOp.SUPER_CALL:
                {
                    gen.addIcode(Icode.CLASS_SUPER_FUNC);
                    gen.stackChange(1);
                    int argc = 0;
                    for (; child != null; child = child.getNext()) {
                        gen.visitExpression(child, 0);
                        argc++;
                    }
                    gen.addIndexOp(Icode.CLASS_SUPER_CALL, argc);
                    gen.addUint8(op.arrayArguments ? 1 : 0);
                    gen.stackChange(-argc);
                    break;
                }
            case ClassOp.THIS:
                gen.addIcode(Icode.CLASS_THIS);
                gen.stackChange(1);
                break;
            case ClassOp.TDZ:
                gen.addStringOp(Icode.CLASS_TDZ, op.name);
                gen.addUint8(op.parameter ? 1 : 0);
                gen.stackChange(1);
                break;
            case ClassOp.ITERATOR_REST:
                gen.visitExpression(child, 0);
                gen.addIcode(Icode.CLASS_ITER_REST);
                break;
            case ClassOp.REQUIRE_OBJECT:
                gen.visitExpression(child, 0);
                gen.addIcode(Icode.CLASS_REQUIRE_OBJECT);
                break;
            case ClassOp.PARAM_SCOPE:
                gen.addStringOp(Icode.CLASS_PARAM_SCOPE, op.name);
                gen.stackChange(1);
                break;
            case ClassOp.ITERATOR_CLOSE:
                {
                    Node localBlock = (Node) node.getProp(Node.LOCAL_BLOCK_PROP);
                    gen.addIndexOp(
                            Icode.CLASS_ITER_CLOSE, localBlock.getExistingIntProp(Node.LOCAL_PROP));
                    gen.addUint8(op.quiet ? 1 : 0);
                    gen.stackChange(1);
                    break;
                }
            case ClassOp.CTOR_CHECK:
                gen.addIcode(Icode.CLASS_CTOR_CHECK);
                gen.stackChange(1);
                break;
            case ClassOp.PRIVATE_NAME:
                gen.addStringOp(Icode.CLASS_PRIVATE_NAME, op.name);
                gen.stackChange(1);
                break;
            case ClassOp.BLOCK_FUNCTION:
                gen.addStringOp(Icode.CLASS_BLOCK_FUNCTION, op.name);
                gen.stackChange(1);
                break;
            case ClassOp.INIT_FIELDS:
                gen.addIcode(Icode.CLASS_INIT_FIELDS);
                gen.stackChange(1);
                break;
            case ClassOp.STATIC_INIT:
                gen.visitExpression(child, 0);
                gen.addIcode(Icode.CLASS_STATIC_INIT);
                break;
            case ClassOp.DESTRUCTURE:
                {
                    Node localBlock = (Node) node.getProp(Node.LOCAL_BLOCK_PROP);
                    if (child != null) {
                        gen.visitExpression(child, 0);
                    }
                    gen.addIndexOp(
                            Icode.CLASS_DESTRUCTURE,
                            localBlock.getExistingIntProp(Node.LOCAL_PROP));
                    gen.addUint8(op.step);
                    if (child == null) {
                        gen.stackChange(1);
                    }
                    break;
                }
            case ClassOp.AWAIT:
                gen.visitExpression(child, 0);
                gen.addIndexOp(Icode.CLASS_AWAIT, Math.max(node.getLineno(), 0));
                break;
            case ClassOp.FOR_AWAIT:
                {
                    Node localBlock = (Node) node.getProp(Node.LOCAL_BLOCK_PROP);
                    if (child != null) {
                        gen.visitExpression(child, 0);
                    }
                    gen.addIndexOp(
                            Icode.CLASS_FOR_AWAIT, localBlock.getExistingIntProp(Node.LOCAL_PROP));
                    gen.addUint8(op.step);
                    if (child == null) {
                        gen.stackChange(1);
                    }
                    break;
                }
            case ClassOp.LOGICAL_NAME:
                {
                    // The binding stays under the value of the operator: step 0 pushes it with the
                    // value of the read, the BINDNAME of the assignment gets its scope from it.
                    Node read = child.getFirstChild();
                    if (!ClassOp.isLogicalName(read, read.getNext())) {
                        gen.visitExpression(child, 0);
                        break;
                    }
                    gen.addStringOp(Icode.CLASS_LOGICAL_BIND, read.getString());
                    gen.addUint8(0);
                    gen.stackChange(1);
                    gen.visitExpression(child, 0);
                    gen.addIcode(Icode.CLASS_LOGICAL_BIND);
                    gen.addUint8(2);
                    gen.stackChange(-1);
                    break;
                }
            case ClassOp.OPTIONAL_CHAIN:
                optionalChain(gen, node, false);
                break;
            default:
                throw Kit.codeBug();
        }
    }

    static void register(InstructionClass[] instructionObjs, int base) {
        instructionObjs[base + Icode.CLASS_DEFINE] = new DoClassDefine();
        instructionObjs[base + Icode.CLASS_ELEMENT] = new DoClassElement();
        instructionObjs[base + Icode.CLASS_CTOR_CHECK] = new DoClassCtorCheck();
        instructionObjs[base + Icode.CLASS_THIS] = new DoClassThis();
        instructionObjs[base + Icode.CLASS_TDZ] = new DoClassTdz();
        instructionObjs[base + Icode.CLASS_SUPER_FUNC] = new DoClassSuperFunc();
        instructionObjs[base + Icode.CLASS_SUPER_CALL] = new DoClassSuperCall();
        instructionObjs[base + Icode.CLASS_ITER_REST] = new DoClassIterRest();
        instructionObjs[base + Icode.CLASS_REQUIRE_OBJECT] = new DoClassRequireObject();
        instructionObjs[base + Icode.CLASS_ITER_CLOSE] = new DoClassIterClose();
        instructionObjs[base + Icode.CLASS_PARAM_SCOPE] = new DoClassParamScope();
        instructionObjs[base + Icode.CLASS_PRIVATE_NAME] = new DoClassPrivateName();
        instructionObjs[base + Icode.CLASS_INIT_FIELDS] = new DoClassInitFields();
        instructionObjs[base + Icode.CLASS_STATIC_INIT] = new DoClassStaticInit();
        instructionObjs[base + Icode.CLASS_DESTRUCTURE] = new DoClassDestructure();
        instructionObjs[base + Icode.CLASS_BOUND_NAME] = new DoClassBoundName();
        instructionObjs[base + Icode.CLASS_LOGICAL_BIND] = new DoClassLogicalBind();
        instructionObjs[base + Icode.CLASS_AWAIT] = new DoClassAwait();
        instructionObjs[base + Icode.CLASS_FOR_AWAIT] = new DoClassForAwait();
        instructionObjs[base + Icode.CLASS_OPTIONAL] = new DoClassOptional();
        instructionObjs[base + Icode.CLASS_OPTIONAL_CALLEE] = new DoClassOptional();
        instructionObjs[base + Icode.CLASS_BLOCK_FUNCTION] = new DoClassBlockFunction();
        SpreadCall.register(instructionObjs, base);
    }

    static String icodeName(int icode) {
        switch (icode) {
            case Icode.CLASS_DEFINE:
                return "CLASS_DEFINE";
            case Icode.CLASS_ELEMENT:
                return "CLASS_ELEMENT";
            case Icode.CLASS_CTOR_CHECK:
                return "CLASS_CTOR_CHECK";
            case Icode.CLASS_THIS:
                return "CLASS_THIS";
            case Icode.CLASS_TDZ:
                return "CLASS_TDZ";
            case Icode.CLASS_SUPER_FUNC:
                return "CLASS_SUPER_FUNC";
            case Icode.CLASS_SUPER_CALL:
                return "CLASS_SUPER_CALL";
            case Icode.CLASS_ITER_REST:
                return "CLASS_ITER_REST";
            case Icode.CLASS_REQUIRE_OBJECT:
                return "CLASS_REQUIRE_OBJECT";
            case Icode.CLASS_ITER_CLOSE:
                return "CLASS_ITER_CLOSE";
            case Icode.CLASS_PARAM_SCOPE:
                return "CLASS_PARAM_SCOPE";
            case Icode.CLASS_PRIVATE_NAME:
                return "CLASS_PRIVATE_NAME";
            case Icode.CLASS_INIT_FIELDS:
                return "CLASS_INIT_FIELDS";
            case Icode.CLASS_STATIC_INIT:
                return "CLASS_STATIC_INIT";
            case Icode.CLASS_DESTRUCTURE:
                return "CLASS_DESTRUCTURE";
            case Icode.CLASS_BOUND_NAME:
                return "CLASS_BOUND_NAME";
            case Icode.CLASS_LOGICAL_BIND:
                return "CLASS_LOGICAL_BIND";
            case Icode.CLASS_AWAIT:
                return "CLASS_AWAIT";
            case Icode.CLASS_FOR_AWAIT:
                return "CLASS_FOR_AWAIT";
            case Icode.CLASS_OPTIONAL:
                return "CLASS_OPTIONAL";
            case Icode.CLASS_BLOCK_FUNCTION:
                return "CLASS_BLOCK_FUNCTION";
            case Icode.CLASS_OPTIONAL_CALLEE:
                return "CLASS_OPTIONAL_CALLEE";
            default:
                return SpreadCall.icodeName(icode);
        }
    }

    private static Object value(CallFrame frame, int index) {
        Object value = frame.stack[index];
        if (value == UniqueTag.DOUBLE_MARK) {
            value = ScriptRuntime.wrapNumber(frame.doubleStack[index]);
        }
        return value;
    }

    private static JSDescriptor<JSFunction> function(CallFrame frame, int index) {
        return frame.fnOrScript.getDescriptor().getFunction(index);
    }

    private static final class DoClassDefine extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            boolean hasHeritage = frame.compilerData.itsICode[frame.pc++] != 0;
            Object heritage = hasHeritage ? value(frame, frame.stackTop--) : null;
            frame.stack[++frame.stackTop] =
                    ClassRuntime.defineClass(
                            heritage,
                            hasHeritage,
                            function(frame, state.indexReg),
                            cx,
                            frame.scope);
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            boolean hasHeritage = ctx.compilerData.itsICode[ctx.pc++] != 0;
            ctx.out.println(tname + " #" + ctx.indexReg + (hasHeritage ? " extends" : ""));
        }
    }

    private static final class DoClassElement extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            int flags = frame.compilerData.itsICode[frame.pc++] & 0xFF;
            Object key = value(frame, frame.stackTop--);
            ClassRuntime.defineElement(
                    frame.stack[frame.stackTop],
                    key,
                    (flags & ClassOp.ELEMENT_NO_VALUE) != 0
                            ? null
                            : function(frame, state.indexReg),
                    flags,
                    cx,
                    frame.scope);
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            int flags = ctx.compilerData.itsICode[ctx.pc++] & 0xFF;
            ctx.out.println(tname + " #" + ctx.indexReg + " flags " + flags);
        }
    }

    private static final class DoClassCtorCheck extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] =
                    ClassRuntime.checkConstructorCall(frame.newTarget, frame.fnOrScript);
            return null;
        }
    }

    private static final class DoClassThis extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] = ClassRuntime.thisValue(frame.thisObj);
            return null;
        }
    }

    private static final class DoClassTdz extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            boolean parameter = frame.compilerData.itsICode[frame.pc++] != 0;
            throw ClassRuntime.tdzError(state.stringReg, parameter);
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            boolean parameter = ctx.compilerData.itsICode[ctx.pc++] != 0;
            ctx.out.println(tname + (parameter ? " parameter" : ""));
        }
    }

    private static final class DoClassIterRest extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            Object iterator = value(frame, frame.stackTop);
            frame.stack[frame.stackTop] = ClassRuntime.iteratorRest(iterator, cx, frame.scope);
            return null;
        }
    }

    private static final class DoClassIterClose extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            boolean quiet = frame.compilerData.itsICode[frame.pc++] != 0;
            Object enumeration = frame.stack[frame.localShift + state.indexReg];
            frame.stack[++frame.stackTop] =
                    ClassRuntime.iteratorClose(enumeration, quiet, cx, frame.scope);
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            boolean quiet = ctx.compilerData.itsICode[ctx.pc++] != 0;
            ctx.out.println(tname + " local " + ctx.indexReg + (quiet ? " quiet" : ""));
        }
    }

    private static final class DoClassDestructure extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            int step = frame.compilerData.itsICode[frame.pc++] & 0xFF;
            int local = frame.localShift + state.indexReg;
            if (step == ArrayDestructuring.OPEN) {
                Object record =
                        ArrayDestructuring.destructure(
                                value(frame, frame.stackTop), step, cx, frame.scope);
                frame.stack[local] = record;
                frame.stack[frame.stackTop] = record;
            } else {
                frame.stack[++frame.stackTop] =
                        ArrayDestructuring.destructure(frame.stack[local], step, cx, frame.scope);
            }
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            int step = ctx.compilerData.itsICode[ctx.pc++] & 0xFF;
            ctx.out.println(tname + " local " + ctx.indexReg + " step " + step);
        }
    }

    private static final class DoClassAwait extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[frame.stackTop] =
                    AsyncRuntime.await(value(frame, frame.stackTop), state.indexReg);
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            ctx.out.println(tname + " line " + ctx.indexReg);
        }
    }

    private static final class DoClassForAwait extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            int step = frame.compilerData.itsICode[frame.pc++] & 0xFF;
            int local = frame.localShift + state.indexReg;
            if (step == ForAwait.OPEN) {
                Object record =
                        ForAwait.step(value(frame, frame.stackTop), null, step, cx, frame.scope);
                frame.stack[local] = record;
                frame.stack[frame.stackTop] = record;
            } else if (ForAwait.hasValue(step)) {
                frame.stack[frame.stackTop] =
                        ForAwait.step(
                                value(frame, frame.stackTop),
                                frame.stack[local],
                                step,
                                cx,
                                frame.scope);
            } else {
                frame.stack[++frame.stackTop] =
                        ForAwait.step(null, frame.stack[local], step, cx, frame.scope);
            }
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            int step = ctx.compilerData.itsICode[ctx.pc++] & 0xFF;
            ctx.out.println(tname + " local " + ctx.indexReg + " step " + step);
        }
    }

    private static final class DoClassOptional extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            Object value = value(frame, frame.stackTop);
            frame.stack[frame.localShift + state.indexReg] = value;
            frame.stack[frame.stackTop] =
                    op == Icode.CLASS_OPTIONAL_CALLEE
                            ? ClassRuntime.optionalCallee((ScriptRuntime.LookupResult) value)
                            : value;
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            ctx.out.println(tname + " local " + ctx.indexReg);
        }
    }

    private static final class DoClassBoundName extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[frame.stackTop] =
                    ClassRuntime.boundValue(
                            frame.stack[frame.stackTop], cx, frame.scope, state.stringReg);
            return null;
        }
    }

    private static final class DoClassLogicalBind extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            Object[] stack = frame.stack;
            int top = frame.stackTop;
            switch (frame.compilerData.itsICode[frame.pc++]) {
                case 0:
                    {
                        Object bound = ClassRuntime.logicalBind(cx, frame.scope, state.stringReg);
                        Object value =
                                ClassRuntime.boundValue(bound, cx, frame.scope, state.stringReg);
                        stack[top + 1] = bound;
                        stack[top + 2] = value;
                        frame.stackTop = top + 2;
                        break;
                    }
                case 1:
                    stack[top + 1] =
                            ClassRuntime.logicalTarget(
                                    stack[top], cx, frame.scope, state.stringReg);
                    frame.stackTop = top + 1;
                    break;
                default:
                    stack[top - 1] = stack[top];
                    frame.doubleStack[top - 1] = frame.doubleStack[top];
                    stack[top] = null;
                    frame.stackTop = top - 1;
                    break;
            }
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            int step = ctx.compilerData.itsICode[ctx.pc++];
            ctx.out.println(tname + " step " + step);
        }
    }

    private static final class DoClassParamScope extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] =
                    ClassRuntime.paramScope(state.stringReg, cx, frame.scope);
            return null;
        }
    }

    private static final class DoClassRequireObject extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            Object value = value(frame, frame.stackTop);
            frame.stack[frame.stackTop] = ClassRuntime.requireObjectCoercible(value);
            return null;
        }
    }

    private static final class DoClassPrivateName extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] = ClassRuntime.newPrivateName(state.stringReg);
            return null;
        }
    }

    private static final class DoClassBlockFunction extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] =
                    ClassRuntime.hoistBlockFunction(frame.scope, state.stringReg);
            return null;
        }
    }

    private static final class DoClassInitFields extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] =
                    ClassRuntime.initializeInstanceElements(frame.thisObj, frame.fnOrScript, cx);
            return null;
        }
    }

    private static final class DoClassStaticInit extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[frame.stackTop] = ClassRuntime.staticInit(frame.stack[frame.stackTop], cx);
            return null;
        }
    }

    private static final class DoClassSuperFunc extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            frame.stack[++frame.stackTop] = ClassRuntime.superConstructor(frame.thisObj);
            return null;
        }
    }

    private static final class DoClassSuperCall extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            boolean array = frame.compilerData.itsICode[frame.pc++] != 0;
            int argc = state.indexReg;
            frame.stackTop -= argc;
            int funcIndex = frame.stackTop;
            Object superConstructor = frame.stack[funcIndex];
            Object result;
            if (array) {
                result =
                        ClassRuntime.superCallArray(
                                superConstructor,
                                frame.stack[funcIndex + 1],
                                cx,
                                frame.scope,
                                frame.thisObj,
                                frame.newTarget);
            } else {
                Object[] args = new Object[argc];
                for (int i = 0; i < argc; i++) {
                    args[i] = value(frame, funcIndex + 1 + i);
                }
                result =
                        ClassRuntime.superCall(
                                superConstructor,
                                args,
                                cx,
                                frame.scope,
                                frame.thisObj,
                                frame.newTarget);
            }
            frame.stack[funcIndex] = result;
            return null;
        }

        @Override
        void dumpICode(int op, String tname, ICodeDumpContext ctx) {
            boolean array = ctx.compilerData.itsICode[ctx.pc++] != 0;
            ctx.out.println(tname + " " + ctx.indexReg + (array ? " array" : ""));
        }
    }
}
