package org.mozilla.javascript.typedarrays;

import java.io.Serial;
import java.math.BigInteger;

public abstract class NativeBigIntArrayView extends NativeTypedArrayView<BigInteger> {
    @Serial private static final long serialVersionUID = -3349222145964894609L;

    protected NativeBigIntArrayView() {}

    protected NativeBigIntArrayView(NativeArrayBuffer ab, int off, int len, int byteLen) {
        super(ab, off, len, byteLen);
    }

    @Override
    protected Object toNumeric(Object num) {
        return TypedArrayKey.toBigInt(num); // AutoJs6: class syntax
    }
}
