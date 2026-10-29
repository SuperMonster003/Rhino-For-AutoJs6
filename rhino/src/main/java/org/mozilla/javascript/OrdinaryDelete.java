/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ScriptRuntime.StringIdOrIndex;
import org.mozilla.javascript.ScriptableObject.DescriptorInfo;

/**
 * AutoJs6: [[Delete]] with its result, for {@code Reflect.deleteProperty} and the {@code delete}
 * operator on a Proxy (ROADMAP P6.14, appendix C Q33). Not related to classes.
 *
 * <p>Upstream's {@link NativeProxy#delete} drops the result of the deleteProperty trap, so both
 * took the result from the has trap afterwards. The delete of a non-configurable property throws in
 * strict code, inside {@code Reflect.deleteProperty} too, which returns false instead.
 */
final class OrdinaryDelete {

    private OrdinaryDelete() {}

    /** o.[[Delete]](key), the key being a String or a Symbol: own properties only. */
    static boolean delete(Context cx, Scriptable o, Object key) {
        while (o instanceof NativeProxy) {
            NativeProxy proxy = (NativeProxy) o;
            ScriptableObject target = proxy.getTargetThrowIfRevoked();
            Function trap = proxy.getTrap("deleteProperty");
            if (trap == null) {
                o = target;
                continue;
            }
            if (!ScriptRuntime.toBoolean(proxy.callTrap(trap, new Object[] {target, key}))) {
                return false;
            }
            DescriptorInfo targetDesc = target.getOwnPropertyDescriptor(cx, key);
            if (targetDesc == null) {
                return true;
            }
            if (targetDesc.isConfigurable(false) || !target.isExtensible()) {
                throw ScriptRuntime.typeError(
                        "proxy can't delete an existing own property '"
                                + key
                                + "' on a not configurable or not extensible object");
            }
            return true;
        }
        if (o instanceof ScriptableObject) {
            DescriptorInfo desc = ((ScriptableObject) o).getOwnPropertyDescriptor(cx, key);
            if (desc != null && desc.isConfigurable(false)) {
                return false;
            }
        }
        if (key instanceof Symbol) {
            Symbol symbol = (Symbol) key;
            SymbolScriptable obj = ScriptableObject.ensureSymbolScriptable(o);
            obj.delete(symbol);
            return !obj.has(symbol, o);
        }
        StringIdOrIndex s = ScriptRuntime.toStringIdOrIndex(key);
        if (s.stringId == null) {
            o.delete(s.index);
            return !o.has(s.index, o);
        }
        o.delete(s.stringId);
        return !o.has(s.stringId, o);
    }

    /**
     * The {@code delete} operator on a Proxy: its [[Delete]], a TypeError for false in strict code.
     */
    static boolean operator(NativeProxy proxy, Object elem, Context cx) {
        Object key = ScriptRuntime.isSymbol(elem) ? elem : ScriptRuntime.toString(elem);
        boolean deleted = delete(cx, proxy, key);
        if (!deleted && cx.isStrictMode()) {
            throw ScriptRuntime.typeError("proxy refused to delete property '" + key + "'");
        }
        return deleted;
    }
}
