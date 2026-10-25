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
 *   <li>{@code CLASS_INIT_FIELDS}: no operand. Initializes the instance elements of the class on
 *       this, pushes undefined.
 *   <li>{@code CLASS_STATIC_INIT}: no operand. Stack: F -&gt; F, once the static elements ran.
 * </ul>
 */
final class ClassInterpreter {

    private ClassInterpreter() {}

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
            case ClassOp.INIT_FIELDS:
                gen.addIcode(Icode.CLASS_INIT_FIELDS);
                gen.stackChange(1);
                break;
            case ClassOp.STATIC_INIT:
                gen.visitExpression(child, 0);
                gen.addIcode(Icode.CLASS_STATIC_INIT);
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
            default:
                return null;
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
