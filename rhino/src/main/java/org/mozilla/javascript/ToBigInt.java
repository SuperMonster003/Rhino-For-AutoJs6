/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.math.BigInteger;

/**
 * AutoJs6: ToBigInt (7.1.13, ROADMAP P6.19, appendix C Q46). Not related to classes.
 *
 * <p>{@link ScriptRuntime#toBigInt(Object)} also serves the {@code BigInt} function, which takes a
 * Number (NumberToBigInt). ToBigInt itself throws a TypeError for one.
 */
public final class ToBigInt {

    private ToBigInt() {}

    /** ToBigInt(value). */
    public static BigInteger of(Object value) {
        Object prim = ScriptRuntime.toPrimitive(value, ScriptRuntime.NumberClass);
        if (prim instanceof Number && !(prim instanceof BigInteger)) {
            throw ScriptRuntime.typeErrorById(
                    "msg.cant.convert.to.bigint", ScriptRuntime.toString(prim));
        }
        return ScriptRuntime.toBigInt(prim);
    }

    /** ToBigInt of {@code args[index]}, which is undefined when missing. */
    public static BigInteger of(Object[] args, int index) {
        return of(index < args.length ? args[index] : Undefined.instance);
    }
}
