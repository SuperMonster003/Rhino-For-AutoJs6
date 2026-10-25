/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.Interpreter.InstructionClass;
import org.mozilla.javascript.Interpreter.InterpreterState;
import org.mozilla.javascript.Interpreter.NewState;

/**
 * AutoJs6: spread arguments in calls, {@code f(...a)}, {@code o.m(x, ...a)}, {@code super.m(...a)}
 * and {@code new F(...a)} (ROADMAP P6.6, appendix C Q16). Not related to classes; a fix of an
 * upstream gap.
 *
 * <p>{@link #lower} moves all arguments of a call with a spread argument into one array literal,
 * which evaluates them from left to right and iterates each spread value like an array literal
 * does. The call keeps its callee, so both backends look the function and this value up as for any
 * call, and gets a single {@link Token#DOTDOTDOT} argument holding the literal. The backends then
 * pass the elements of the literal as the arguments: the interpreter with the icodes below, the
 * compiler by {@link #args} in place of the argument array.
 *
 * <p>Icodes, none with an operand byte:
 *
 * <ul>
 *   <li>{@code SPREAD_CALL}, {@code SPREAD_CALL_ON_SUPER}: index register = 1. Stack: lookup
 *       result, arguments -&gt; result. Run by the instruction of {@link Token#CALL}, which takes
 *       the elements as bound arguments, so interpreted callees still get an inline frame.
 *   <li>{@code SPREAD_NEW}: index register = 1 for {@code new eval(...)}, else 0. Stack:
 *       constructor, arguments -&gt; object.
 *   <li>{@code SPREAD_CALLSPECIAL}: index register = the line. Stack: lookup result, arguments
 *       -&gt; result of the possibly direct {@code eval(...)}.
 *   <li>{@code SPREAD_REF_CALL}: no operand. Stack: lookup result, arguments -&gt; reference.
 * </ul>
 */
public final class SpreadCall {

    /** The instruction count of a call, as in {@link Interpreter}. */
    private static final int INVOCATION_COST = 100;

    private SpreadCall() {}

    /** Gives a call or {@code new} with spread arguments its single array argument. */
    static void lower(Node call) {
        Node target = call.getFirstChild();
        boolean spread = false;
        for (Node arg = target.getNext(); arg != null; arg = arg.getNext()) {
            spread |= arg.getType() == Token.DOTDOTDOT;
        }
        if (!spread) {
            return;
        }
        Node array = new Node(Token.ARRAYLIT);
        array.setLineColumnNumber(call.getLineno(), call.getColumn());
        int spreads = 0;
        int count = 0;
        for (Node arg = target.getNext(); arg != null; ) {
            Node next = arg.getNext();
            call.removeChild(arg);
            array.addChildToBack(arg);
            if (arg.getType() == Token.DOTDOTDOT) {
                spreads++;
            }
            count++;
            arg = next;
        }
        array.putIntProp(Node.NUMBER_OF_SPREAD, spreads);
        array.putIntProp(Node.DESTRUCTURING_ARRAY_LENGTH, count);
        Node argument = new Node(Token.DOTDOTDOT, array);
        argument.setLineColumnNumber(call.getLineno(), call.getColumn());
        call.addChildToBack(argument);
    }

    /** Returns whether the argument is the array of a lowered call. */
    public static boolean isSpread(Node argument) {
        return argument != null && argument.getType() == Token.DOTDOTDOT;
    }

    /** Returns whether a call or {@code new} node was lowered by {@link #lower}. */
    public static boolean isSpreadCall(Node call) {
        Node target = call.getFirstChild();
        return target != null && isSpread(target.getNext());
    }

    /** The expression a backend evaluates for an argument: the array of a lowered call. */
    static Node argument(Node argument) {
        return isSpread(argument) ? argument.getFirstChild() : argument;
    }

    /**
     * Emits the call icode of a lowered call or {@code new}, after the callee and the array. The
     * caller adjusts the stack as for one argument.
     */
    static <T extends ScriptOrFn<T>> void emit(
            CodeGenerator<T> gen, Node node, int type, int line) {
        int callType = node.getIntProp(Node.SPECIALCALL_PROP, Node.NON_SPECIALCALL);
        if (type == Token.NEW) {
            gen.addIndexOp(Icode.SPREAD_NEW, callType == Node.NON_SPECIALCALL ? 0 : 1);
        } else if (type == Token.REF_CALL) {
            gen.addIcode(Icode.SPREAD_REF_CALL);
        } else if (callType != Node.NON_SPECIALCALL) {
            gen.addIndexOp(Icode.SPREAD_CALLSPECIAL, line);
        } else if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
            gen.addIndexOp(Icode.SPREAD_CALL_ON_SUPER, 1);
        } else {
            gen.addIndexOp(Icode.SPREAD_CALL, 1);
        }
    }

    static void register(InstructionClass[] instructionObjs, int base) {
        instructionObjs[base + Icode.SPREAD_CALL] = instructionObjs[base + Token.CALL];
        instructionObjs[base + Icode.SPREAD_CALL_ON_SUPER] = instructionObjs[base + Token.CALL];
        instructionObjs[base + Icode.SPREAD_NEW] = new DoSpreadNew();
        instructionObjs[base + Icode.SPREAD_CALLSPECIAL] = new DoSpreadCallSpecial();
        instructionObjs[base + Icode.SPREAD_REF_CALL] = new DoSpreadRefCall();
    }

    static String icodeName(int icode) {
        switch (icode) {
            case Icode.SPREAD_CALL:
                return "SPREAD_CALL";
            case Icode.SPREAD_CALL_ON_SUPER:
                return "SPREAD_CALL_ON_SUPER";
            case Icode.SPREAD_NEW:
                return "SPREAD_NEW";
            case Icode.SPREAD_CALLSPECIAL:
                return "SPREAD_CALLSPECIAL";
            case Icode.SPREAD_REF_CALL:
                return "SPREAD_REF_CALL";
            default:
                return null;
        }
    }

    /** Returns whether the instruction of {@link Token#CALL} runs a lowered call. */
    static boolean isCall(int op) {
        return op == Icode.SPREAD_CALL || op == Icode.SPREAD_CALL_ON_SUPER;
    }

    /** The call icode the instruction of {@link Token#CALL} runs a lowered call as. */
    static int callOp(int op) {
        return op == Icode.SPREAD_CALL ? Token.CALL : Icode.CALL_ON_SUPER;
    }

    /**
     * For the instruction of {@link Token#CALL} running {@code SPREAD_CALL}: returns the arguments
     * in the array above the lookup result at {@code frame.stackTop}, which become the bound
     * arguments of the call, and sets the argument count. The first argument replaces the array on
     * the stack, where a continuation called this way looks for its result.
     */
    static Object[] callArguments(CallFrame frame, InterpreterState state) {
        int index = frame.stackTop + 1;
        Object[] args = args(frame.stack[index]);
        frame.stack[index] = args.length == 0 ? Undefined.instance : args[0];
        state.indexReg = args.length;
        return args;
    }

    /** The elements of the array a lowered call evaluates, its arguments. */
    public static Object[] args(Object array) {
        NativeArray arguments = (NativeArray) array;
        long length = arguments.getLength();
        if (length == 0) {
            return ScriptRuntime.emptyArgs;
        }
        Object[] args = new Object[(int) length];
        for (int i = 0; i < args.length; i++) {
            Object value = arguments.get(i, arguments);
            args[i] = value == Scriptable.NOT_FOUND ? Undefined.instance : value;
        }
        return args;
    }

    private static final class DoSpreadNew extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            if (state.instructionCounting) {
                cx.instructionCount += INVOCATION_COST;
            }
            boolean special = state.indexReg != 0;
            int index = --frame.stackTop;
            Object constructor = frame.stack[index];
            if (constructor == UniqueTag.DOUBLE_MARK) {
                constructor = ScriptRuntime.wrapNumber(frame.doubleStack[index]);
            }
            Object[] args = args(frame.stack[index + 1]);
            frame.stack[index] =
                    special
                            ? ScriptRuntime.newSpecial(
                                    cx, constructor, args, frame.scope, Node.SPECIALCALL_EVAL)
                            : ScriptRuntime.newObject(constructor, cx, frame.scope, args);
            return null;
        }
    }

    private static final class DoSpreadCallSpecial extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            if (state.instructionCounting) {
                cx.instructionCount += INVOCATION_COST;
            }
            int index = --frame.stackTop;
            ScriptRuntime.LookupResult result = (ScriptRuntime.LookupResult) frame.stack[index];
            Object[] args = args(frame.stack[index + 1]);
            frame.stack[index] =
                    ScriptRuntime.callSpecial(
                            cx,
                            result.getCallable(),
                            result.getThis(),
                            args,
                            frame.scope,
                            frame.thisObj,
                            Node.SPECIALCALL_EVAL,
                            frame.fnOrScript.getDescriptor().getSourceName(),
                            state.indexReg,
                            false);
            return null;
        }
    }

    private static final class DoSpreadRefCall extends InstructionClass {
        @Override
        NewState execute(Context cx, CallFrame frame, InterpreterState state, int op) {
            if (state.instructionCounting) {
                cx.instructionCount += INVOCATION_COST;
            }
            int index = --frame.stackTop;
            ScriptRuntime.LookupResult result = (ScriptRuntime.LookupResult) frame.stack[index];
            Object[] args = args(frame.stack[index + 1]);
            frame.stack[index] =
                    ScriptRuntime.callRef(result.getCallable(), result.getThis(), args, cx);
            return null;
        }
    }
}
