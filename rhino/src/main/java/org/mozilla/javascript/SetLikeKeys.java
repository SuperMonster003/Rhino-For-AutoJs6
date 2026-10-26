/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: the keys iterator of a set-like argument of the {@link NativeSet} methods (ROADMAP
 * P6.11, appendix C Q23). Not related to classes.
 *
 * <p>Upstream took the result of {@code other.keys()} as an iterable and called its {@code
 * Symbol.iterator}, so an iterator with only {@code next} threw. The spec (GetIteratorFromMethod in
 * GetSetRecord's users) takes the result itself as the iterator and reads its {@code next}. {@link
 * #iterator} replaces {@link ScriptRuntime#callIterator} there and takes the same arguments.
 */
final class SetLikeKeys {

    private SetLikeKeys() {}

    /** The result of {@code keys()} as the iterator; a TypeError when it is not an object. */
    static Object iterator(Object keys, Context cx, VarScope scope) {
        if (!(keys instanceof Scriptable) || keys instanceof Symbol) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(keys));
        }
        return keys;
    }
}
