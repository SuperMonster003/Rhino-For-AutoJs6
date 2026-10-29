/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: [[SetPrototypeOf]] with its result, for {@code Object.setPrototypeOf}, the {@code
 * __proto__} setter and {@code Reflect.setPrototypeOf} on a Proxy (ROADMAP P6.15, appendix C Q34).
 * Not related to classes.
 *
 * <p>Upstream looked for a cycle with the getPrototypeOf trap of every Proxy in the new chain,
 * where OrdinarySetPrototypeOf stops at a Proxy, and asked a Proxy for its prototype and
 * extensibility (traps) before its setPrototypeOf trap. The result of the trap was dropped, a
 * non-extensible target was not compared with the new prototype, and without a trap the prototype
 * of the target was set without the checks of the target.
 */
final class OrdinarySetPrototype {

    private OrdinarySetPrototype() {}

    /** o.[[SetPrototypeOf]](proto). */
    static boolean set(Scriptable o, Scriptable proto) {
        if (o instanceof NativeProxy) {
            return proxySet((NativeProxy) o, proto);
        }
        if (o.getPrototype() == proto) {
            return true;
        }
        if (o instanceof ScriptableObject && !((ScriptableObject) o).isExtensible()) {
            return false;
        }
        for (Scriptable p = proto; p != null; p = OrdinaryGet.next(p)) {
            if (p == o) {
                return false;
            }
        }
        o.setPrototype(proto);
        return true;
    }

    /** {@code Object.setPrototypeOf} and the {@code __proto__} setter on a Proxy. */
    static Object object(Object proxy, Scriptable proto) {
        if (!set((NativeProxy) proxy, proto)) {
            throw ScriptRuntime.typeError("proxy refused to set the prototype");
        }
        return proxy;
    }

    /** {@code Reflect.setPrototypeOf(target, proto)} once the target is known to be an object. */
    static boolean reflect(Scriptable target, Object proto) {
        if (proto != null && (!(proto instanceof Scriptable) || ScriptRuntime.isSymbol(proto))) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.brief(proto));
        }
        return set(target, (Scriptable) proto);
    }

    /** proxy.[[SetPrototypeOf]](proto) (10.5.2). */
    private static boolean proxySet(NativeProxy proxy, Scriptable proto) {
        ScriptableObject target = proxy.getTargetThrowIfRevoked();
        Function trap = proxy.getTrap("setPrototypeOf");
        if (trap == null) {
            return set(target, proto);
        }
        if (!ScriptRuntime.toBoolean(proxy.callTrap(trap, new Object[] {target, proto}))) {
            return false;
        }
        if (target.isExtensible()) {
            return true;
        }
        if (target.getPrototype() != proto) {
            throw ScriptRuntime.typeError(
                    "proxy can't set a prototype different from the one of a non-extensible target");
        }
        return true;
    }
}
