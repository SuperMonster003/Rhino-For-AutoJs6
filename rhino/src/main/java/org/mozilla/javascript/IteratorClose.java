/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: IteratorClose for {@link IteratorLikeIterable} (ROADMAP P6.10, appendix C Q21 / Q22).
 * Not related to classes.
 *
 * <p>Upstream looked up {@code return} when it got the iterator and called it in {@code close},
 * also after the iterator was done or had thrown. The spec looks it up only when it closes, and
 * closes only when the consumer stops before the iterator is done: a {@code break}, an early
 * result, an error of the consumer. {@link IteratorLikeIterable} now keeps a flag that is set while
 * it asks the iterator for a result (so an error of {@code next}, {@code done} or {@code value}
 * leaves it set) and when the result is done, and calls {@link #close} only without it. The result
 * of {@code return} is not checked, as before; an error of {@code close} after an error of the
 * consumer is suppressed by try-with-resources, so the consumer's error wins.
 */
final class IteratorClose {

    private IteratorClose() {}

    /** Calls {@code return} of the iterator if it has one. */
    static void close(Scriptable iterator, Context cx, VarScope scope) {
        Object method =
                ScriptRuntime.getObjectPropNoWarn(iterator, ES6Iterator.RETURN_PROPERTY, cx, scope);
        if (method == null || Undefined.isUndefined(method)) {
            return;
        }
        if (!(method instanceof Callable)) {
            throw ScriptRuntime.notFunctionError(iterator, method, ES6Iterator.RETURN_PROPERTY);
        }
        ((Callable) method).call(cx, scope, iterator, ScriptRuntime.emptyArgs);
    }
}
