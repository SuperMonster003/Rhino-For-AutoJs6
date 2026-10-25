/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.classfile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Typed save and restore of the operand stack around a yield point of a compiled generator (AutoJs6
 * class syntax, ROADMAP Q10).
 *
 * <p>A generator suspends with entries still on the operand stack, for example inside the arguments
 * of a call: it stores them into an {@code Object[]} and loads them back when it resumes. The
 * writer does not know the types of stack entries while it emits code, so every spilled entry gets
 * a few no-ops before its store and after its load. When the method is done, the stack map analysis
 * finds the type of each entry, and the no-ops become boxing before the store and a cast or
 * unboxing after the load.
 */
public final class StackSpills {
    private static final int STORE_LENGTH = 3;
    private static final int LOAD_LENGTH = 6;
    private static final int UNSET = -1;

    /** {@link #visit} result: no other block has to run again. */
    static final int NONE = -1;

    /** {@link #visit} result: the type of a loaded entry is not known yet. */
    static final int UNKNOWN = -2;

    private final ClassFileWriter cfw;
    private final ConstantPool pool;
    private final Map<Integer, Integer> stores = new HashMap<>();
    private final Map<Integer, Integer> loads = new HashMap<>();
    private int[] storeBcis = new int[8];
    private int[] loadBcis = new int[8];
    private int[] types = new int[8];
    private int size;

    StackSpills(ClassFileWriter cfw, ConstantPool pool) {
        this.cfw = cfw;
        this.pool = pool;
    }

    /** Reserves {@code count} entries and returns the index of the first. */
    public int begin(int count) {
        int first = size;
        size += count;
        if (size > types.length) {
            int length = Math.max(size, types.length * 2);
            storeBcis = Arrays.copyOf(storeBcis, length);
            loadBcis = Arrays.copyOf(loadBcis, length);
            types = Arrays.copyOf(types, length);
        }
        Arrays.fill(types, first, size, UNSET);
        return first;
    }

    /** Emits the placeholder before the store of an entry, whose value is on top of the stack. */
    public void store(int first, int slot) {
        storeBcis[first + slot] = cfw.getCurrentCodeOffset();
        stores.put(cfw.getCurrentCodeOffset(), first + slot);
        nops(STORE_LENGTH);
    }

    /** Emits the placeholder after the load of an entry, whose value is on top of the stack. */
    public void load(int first, int slot) {
        loadBcis[first + slot] = cfw.getCurrentCodeOffset();
        loads.put(cfw.getCurrentCodeOffset(), first + slot);
        nops(LOAD_LENGTH);
    }

    private void nops(int count) {
        for (int i = 0; i < count; i++) {
            cfw.add(ByteCode.NOP);
        }
    }

    /**
     * Steps the stack map analysis at {@code bci}: records the type of a stored entry and gives a
     * loaded entry its type back.
     *
     * @return {@link #NONE}, {@link #UNKNOWN} when the block has to wait for the store, or the
     *     offset of a load whose block has to run again because the stored type changed
     */
    int visit(int bci, int[] stack, int stackTop) {
        Integer entry = stores.get(bci);
        if (entry != null) {
            int type = stack[stackTop - 1];
            int old = types[entry];
            int merged = old == UNSET ? type : TypeInfo.merge(old, type, pool);
            if (merged == old) {
                return NONE;
            }
            types[entry] = merged;
            return loadBcis[entry];
        }
        entry = loads.get(bci);
        if (entry != null) {
            if (types[entry] == UNSET) {
                return UNKNOWN;
            }
            stack[stackTop - 1] = types[entry];
        }
        return NONE;
    }

    /** Replaces the placeholders with the conversions that the analysed types need. */
    void patch(byte[] code) {
        for (int i = 0; i < size; i++) {
            int type = types[i];
            if (type == UNSET) {
                continue;
            }
            switch (TypeInfo.getTag(type)) {
                case TypeInfo.INTEGER:
                    box(code, i, "java/lang/Integer", "I", "intValue");
                    break;
                case TypeInfo.FLOAT:
                    box(code, i, "java/lang/Float", "F", "floatValue");
                    break;
                case TypeInfo.OBJECT_TAG:
                    if (!"java/lang/Object".equals(TypeInfo.getPayloadAsType(type, pool))) {
                        put(code, loadBcis[i], ByteCode.CHECKCAST, TypeInfo.getPayload(type));
                    }
                    break;
                default:
                    // null, wide and uninitialized entries keep the old untyped behaviour
                    break;
            }
        }
    }

    private void box(byte[] code, int entry, String box, String primitive, String unbox) {
        int valueOf = pool.addMethodRef(box, "valueOf", "(" + primitive + ")L" + box + ";");
        put(code, storeBcis[entry], ByteCode.INVOKESTATIC, valueOf);
        put(code, loadBcis[entry], ByteCode.CHECKCAST, pool.addClass(box));
        int value = pool.addMethodRef(box, unbox, "()" + primitive);
        put(code, loadBcis[entry] + 3, ByteCode.INVOKEVIRTUAL, value);
    }

    private static void put(byte[] code, int bci, int opcode, int index) {
        code[bci] = (byte) opcode;
        code[bci + 1] = (byte) (index >> 8);
        code[bci + 2] = (byte) index;
    }
}
